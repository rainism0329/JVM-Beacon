package dev.jvmbeacon.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.ClassUtil;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/** Never guesses a file from its basename alone. */
final class SourceNavigator {
    private static final int MAX_CLASS_NAME = 2_048;
    private static final int MAX_NESTING = 64;

    static void navigate(Project project, Disposable owner, StackTraceElement frame, Consumer<String> status) {
        if (frame.getFileName() == null || frame.getLineNumber() < 1) {
            status.accept("This frame has no source file or usable line number.");
            return;
        }
        if (DumbService.isDumb(project)) {
            status.accept("The IDE is indexing. Try source navigation when indexing completes.");
            return;
        }
        String binaryName = frame.getClassName();
        if (binaryName.length() > MAX_CLASS_NAME || binaryName.chars().filter(c -> c == '$').count() > MAX_NESTING) {
            status.accept("The class name exceeds the bounded source lookup limit. Automatic navigation is unavailable.");
            return;
        }
        ReadAction.nonBlocking(() -> resolve(project, frame))
                .inSmartMode(project).expireWith(owner).finishOnUiThread(ModalityState.any(), result -> {
            if (project.isDisposed()) return;
            Set<VirtualFile> candidates = result.files();
            if (candidates.size() != 1) {
                status.accept(candidates.isEmpty() ? "No source matches the class, filename and line number. Check attached sources and the target version."
                        : "Multiple source files match. Resolve duplicate dependencies before retrying.");
                return;
            }
            if (result.enclosingFallback() && Messages.showOkCancelDialog(project,
                    "Only a candidate line in the enclosing source was found. Anonymous/local class numbering and the source-to-bytecode version match are unverified.\n"
                            + "Frame class: " + frame.getClassName() + "\nSource: " + frame.getFileName() + ":" + frame.getLineNumber()
                            + "\nOpen this candidate location?",
                    "Confirm Candidate Source", "Open Candidate", "Cancel", Messages.getQuestionIcon()) != Messages.OK) {
                status.accept("Candidate navigation cancelled. Anonymous/local class ownership has not been fully verified.");
                return;
            }
            if (project.isDisposed()) return;
            new OpenFileDescriptor(project, candidates.iterator().next(), frame.getLineNumber() - 1, 0).navigate(true);
            status.accept(result.enclosingFallback()
                    ? "Opened the confirmed candidate in the enclosing source. Anonymous/local class numbering and the source-to-bytecode version match remain unverified."
                    : "Located by exact class ownership, filename and line. Verify that these sources match the running bytecode version.");
        }).submit(AppExecutorUtil.getAppExecutorService());
    }

    private record Resolution(Set<VirtualFile> files, boolean enclosingFallback) { }

    private static Resolution resolve(Project project, StackTraceElement frame) {
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        String binaryName = frame.getClassName();
        Set<PsiClass> exact = namedClasses(facade, scope, binaryName);
        // A real class containing '$' wins even if its source is missing/mismatched. Never reinterpret
        // it as an anonymous class simply to find some other file that happens to match the frame.
        if (!exact.isEmpty()) return new Resolution(sourceFiles(project, exact, frame, false), false);

        Set<PsiClass> enclosing = new LinkedHashSet<>();
        for (int separator = binaryName.indexOf('$', binaryName.lastIndexOf('.') + 1);
             separator >= 0; separator = binaryName.indexOf('$', separator + 1)) {
            if (compilerGeneratedSuffix(binaryName.substring(separator + 1))) {
                enclosing.addAll(namedClasses(facade, scope, binaryName.substring(0, separator)));
            }
        }
        return new Resolution(sourceFiles(project, enclosing, frame, true), true);
    }

    /** Resolve actual declarations, not a global '$' -> '.' rewrite (both classes and packages may contain '$'). */
    private static Set<PsiClass> namedClasses(JavaPsiFacade facade, GlobalSearchScope scope, String binaryName) {
        Set<PsiClass> result = new LinkedHashSet<>();
        for (PsiClass candidate : facade.findClasses(binaryName, scope)) {
            if (binaryName.equals(ClassUtil.getJVMClassName(candidate))) result.add(candidate);
        }
        // Each possible outer name is looked up exactly. Its declared members retain their literal
        // names, so Foo$Bar.Inner$Name and Foo.Bar$Inner.Name are not conflated.
        for (int separator = binaryName.indexOf('$', binaryName.lastIndexOf('.') + 1);
             separator >= 0; separator = binaryName.indexOf('$', separator + 1)) {
            String outerName = binaryName.substring(0, separator);
            for (PsiClass outer : facade.findClasses(outerName, scope)) {
                if (outerName.equals(ClassUtil.getJVMClassName(outer))) {
                    collectMembers(outer, binaryName.substring(separator + 1), binaryName, result, 0);
                }
            }
        }
        return result;
    }

    private static void collectMembers(PsiClass outer, String suffix, String binaryName,
                                       Set<PsiClass> result, int depth) {
        if (depth >= MAX_NESTING) return;
        for (PsiClass member : outer.getInnerClasses()) {
            String name = member.getName();
            if (name == null) continue;
            if (suffix.equals(name) && binaryName.equals(ClassUtil.getJVMClassName(member))) result.add(member);
            else if (suffix.startsWith(name + "$")) {
                collectMembers(member, suffix.substring(name.length() + 1), binaryName, result, depth + 1);
            }
        }
    }

    /** Only compiler-style $1, $2Local, $1$Member, etc. qualify; hidden/lambda names are not guessed. */
    static boolean compilerGeneratedSuffix(String suffix) {
        String[] parts = suffix.split("\\$", -1);
        if (parts.length == 0 || parts.length > MAX_NESTING || !numberedClassPart(parts[0])) return false;
        for (int i = 1; i < parts.length; i++) {
            if (!identifier(parts[i]) && !numberedClassPart(parts[i])) return false;
        }
        return true;
    }

    private static boolean numberedClassPart(String text) {
        if (text.isEmpty() || text.charAt(0) < '1' || text.charAt(0) > '9') return false;
        int offset = 1;
        while (offset < text.length() && text.charAt(offset) >= '0' && text.charAt(offset) <= '9') offset++;
        return offset == text.length() || identifier(text.substring(offset));
    }

    private static boolean identifier(String text) {
        if (text.isEmpty() || !Character.isJavaIdentifierStart(text.codePointAt(0))) return false;
        for (int offset = Character.charCount(text.codePointAt(0)); offset < text.length();) {
            int point = text.codePointAt(offset);
            if (!Character.isJavaIdentifierPart(point)) return false;
            offset += Character.charCount(point);
        }
        return true;
    }

    private static Set<VirtualFile> sourceFiles(Project project, Set<PsiClass> classes,
                                                StackTraceElement frame, boolean requireEnclosingRange) {
        Set<VirtualFile> result = new LinkedHashSet<>();
        for (PsiClass psiClass : classes) {
            // Compiled library/JDK declarations navigate to their attached source mirror when available.
            PsiElement navigation = psiClass.getNavigationElement();
            PsiFile file = navigation instanceof PsiFile source ? source : navigation.getContainingFile();
            if (file == null || !frame.getFileName().equals(file.getName()) || file.getVirtualFile() == null) continue;
            var document = PsiDocumentManager.getInstance(project).getDocument(file);
            if (document == null || frame.getLineNumber() > document.getLineCount()) continue;
            if (navigation instanceof PsiClass sourceClass) {
                var range = sourceClass.getTextRange();
                if (range == null || range.isEmpty() || range.getEndOffset() > document.getTextLength()) continue;
                int line = frame.getLineNumber() - 1;
                if (line < document.getLineNumber(range.getStartOffset())
                        || line > document.getLineNumber(range.getEndOffset() - 1)) continue;
            } else if (requireEnclosingRange) continue; // No verified source class means no outer-class fallback.
            result.add(file.getVirtualFile());
        }
        return result;
    }
}
