package dev.jvmbeacon.core;

import javax.management.*;
import java.util.ArrayList;
import java.util.List;

/** Bounded metadata only: no getters and no retained server descriptors. */
public record MBeanMetadata(String description, List<Attribute> attributes, List<MBeanOperationInfo> operations,
                            int omittedAttributes, int omittedOperations, long start, long end) {
    public record Attribute(String name, String type, boolean readable, boolean writable, String description) { }
    public MBeanMetadata { attributes = List.copyOf(attributes); operations = List.copyOf(operations); }

    public static MBeanMetadata read(JmxClient client, String bean) throws Exception {
        long start = System.currentTimeMillis();
        MBeanInfo info = client.info(bean);
        long end = System.currentTimeMillis();
        var attributes = new ArrayList<Attribute>();
        var operations = new ArrayList<MBeanOperationInfo>();
        // Descriptions share a character budget. Names/signatures are never shortened for invocation.
        int remaining = 262_144;
        String description = bounded(info.getDescription(), Math.min(4096, remaining)); remaining -= description.length();
        for (MBeanAttributeInfo a : info.getAttributes()) {
            if (attributes.size() == 1000 || !key(a.getName()) || !key(a.getType())) continue;
            String text = bounded(a.getDescription(), Math.min(1024, remaining)); remaining -= text.length();
            attributes.add(new Attribute(a.getName(), a.getType(), a.isReadable(), a.isWritable(), text));
        }
        for (MBeanOperationInfo op : info.getOperations()) {
            if (operations.size() == 512 || !key(op.getName()) || !key(op.getReturnType())) continue;
            MBeanParameterInfo[] signature = op.getSignature();
            if (signature.length > 32) continue;
            boolean valid = true;
            for (MBeanParameterInfo p : signature) if (!key(p.getName()) || !key(p.getType())) valid = false;
            if (!valid) continue;
            MBeanParameterInfo[] copy = new MBeanParameterInfo[signature.length];
            for (int i = 0; i < copy.length; i++) {
                String text = bounded(signature[i].getDescription(), Math.min(256, remaining)); remaining -= text.length();
                copy[i] = new MBeanParameterInfo(signature[i].getName(), signature[i].getType(), text);
            }
            String text = bounded(op.getDescription(), Math.min(1024, remaining)); remaining -= text.length();
            operations.add(new MBeanOperationInfo(op.getName(), text, copy, op.getReturnType(), op.getImpact()));
        }
        return new MBeanMetadata(description, attributes, operations, info.getAttributes().length - attributes.size(),
                info.getOperations().length - operations.size(), start, end);
    }

    private static boolean key(String text) { return text != null && !text.isEmpty() && text.length() <= 1024; }
    private static String bounded(String text, int limit) {
        if (text == null || limit == 0) return "";
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }
}
