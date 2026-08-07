package ch.castleridge.javals.analysis.ecj;

import org.eclipse.jdt.internal.compiler.env.IBinaryNestedType;

final class IndexBinaryNestedType implements IBinaryNestedType {
    private final char[] enclosingTypeName;
    private final char[] name;
    private final int modifiers;

    IndexBinaryNestedType(String enclosingJvm, String memberJvm, int modifiers) {
        this.enclosingTypeName = enclosingJvm.toCharArray();
        this.name = memberJvm.toCharArray();
        this.modifiers = modifiers;
    }

    @Override
    public char[] getEnclosingTypeName() {
        return enclosingTypeName;
    }

    @Override
    public int getModifiers() {
        return modifiers;
    }

    @Override
    public char[] getName() {
        return name;
    }
}
