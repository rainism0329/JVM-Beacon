package dev.jvmbeacon.ui;

import com.intellij.ide.util.PropertiesComponent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Non-sensitive, application-wide UI preferences only. */
final class BeaconSettings {
    private static final String FAVORITES = "dev.jvmbeacon.favoriteObjectNames";

    static Set<String> favorites() {
        List<String> saved = PropertiesComponent.getInstance().getList(FAVORITES);
        return saved == null ? new LinkedHashSet<>() : new LinkedHashSet<>(saved.stream().limit(200).toList());
    }

    static void saveFavorites(Set<String> names) {
        PropertiesComponent.getInstance().setList(FAVORITES, names.stream().limit(200).toList());
    }
}
