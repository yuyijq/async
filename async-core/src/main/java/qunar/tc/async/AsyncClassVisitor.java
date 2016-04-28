package qunar.tc.async;


import qunar.tc.async.org.objectweb.asm.*;
import qunar.tc.async.org.objectweb.asm.tree.AbstractInsnNode;
import qunar.tc.async.org.objectweb.asm.tree.InsnList;
import qunar.tc.async.org.objectweb.asm.tree.MethodInsnNode;
import qunar.tc.async.org.objectweb.asm.tree.MethodNode;
import qunar.tc.async.org.objectweb.asm.util.CheckClassAdapter;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Created by zhaohui.yu
 * 6/8/15
 */
public class AsyncClassVisitor extends ClassVisitor implements Opcodes {
    private Map<String, String> method2InnerClasses = new HashMap<String, String>();
    private Map<String, MethodNode> name2Node = new HashMap<String, MethodNode>();

    private int index = 0;

    private String parentPath;
    private final List<MethodNode> methods;

    private String className;
    private String classDesc;
    private final Set<Callee> callees;
    private final Set<FieldAccess> fieldAccesses;

    public AsyncClassVisitor(String parentPath, ClassVisitor cw, List<MethodNode> methods, Set<Callee> callees, Set<FieldAccess> fieldAccesses) {
        super(ASM5, cw);
        this.callees = callees;
        this.fieldAccesses = fieldAccesses;
        this.parentPath = parentPath.substring(0, parentPath.lastIndexOf(".class"));
        this.methods = methods;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        super.visit(version, access, name, signature, superName, interfaces);
        this.className = name;
        this.classDesc = "L" + this.className + ";";
        for (MethodNode method : methods) {
            String internalName = method.name + "_" + (index++);
            String key = method.name + "-" + method.desc;
            method2InnerClasses.put(key, internalName);
            name2Node.put(key, method);
            String innerClassName = innerClassName(className, internalName);
            visitInnerClass(innerClassName, name, internalName, ACC_PRIVATE + ACC_STATIC);
        }
    }

    @Override
    public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
        if (!Modifier.isPrivate(access)) return super.visitField(access, name, desc, signature, value);

        if (!fieldAccesses.contains(new FieldAccess(className, name, desc)))
            return super.visitField(access, name, desc, signature, value);

        return super.visitField(Access.of(access).remove(ACC_PRIVATE).add(ACC_PUBLIC).get(), name, desc, signature, value);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        String key = name + "-" + desc;
        MethodNode methodNode = name2Node.get(key);
        String internalName = method2InnerClasses.get(key);
        if (internalName == null) {
            return processPrivateMethodCall(access, name, desc, signature, exceptions);
        }

        int task1Count = 0;

        int task2Count = 0;

        ClassWriter cv = new ClassWriter(0);
        ClassVisitor cw = new CheckClassAdapter(cv);

        String innerClassName = innerClassName(className, internalName);
        cw.visit(V1_6, ACC_SUPER, innerClassName, null, "java/lang/Object", new String[]{"java/lang/Runnable"});
        cw.visitInnerClass(innerClassName, this.className, internalName, ACC_PRIVATE + ACC_STATIC);

        FieldVisitor fv = cw.visitField(ACC_PRIVATE, "state", "I", null, null);
        fv.visitEnd();


        fv = cw.visitField(ACC_PRIVATE + ACC_FINAL, "$stack", Util.STACK_DESC, null, null);
        fv.visitEnd();

        InsnList instructions = methodNode.instructions;
        for (int i = 0; i < instructions.size(); ++i) {
            AbstractInsnNode insnNode = instructions.get(i);
            if (insnNode instanceof MethodInsnNode) {
                MethodInsnNode methodInsnNode = (MethodInsnNode) insnNode;

                if (Util.isAwait1(methodInsnNode)) {
                    int idx = task1Count++;
                    String fName = "$task1_" + idx;
                    FieldVisitor fieldVisitor = cw.visitField(ACC_PRIVATE, fName, Util.TASK1_DESC, Util.TASK1_DESC, null);
                    fieldVisitor.visitEnd();

                    MethodVisitor mv = cw.visitMethod(ACC_PRIVATE + ACC_STATIC, "$set" + fName, "(" + Util.TASK1_DESC + "L" + innerClassName + ";)V", null, null);
                    mv.visitCode();
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitFieldInsn(PUTFIELD, innerClassName, fName, Util.TASK1_DESC);
                    mv.visitInsn(RETURN);
                    mv.visitMaxs(2, 2);
                    mv.visitEnd();
                }

                if (Util.isAwait2(methodInsnNode)) {
                    int idx = task2Count++;
                    String fName = "$task2_" + idx;
                    FieldVisitor fieldVisitor = cw.visitField(ACC_PRIVATE, fName, Util.TASK2_DESC, Util.TASK2_DESC, null);
                    fieldVisitor.visitEnd();

                    MethodVisitor mv = cw.visitMethod(ACC_PRIVATE + ACC_STATIC, "$set" + fName, "(" + Util.TASK2_DESC + "L" + innerClassName + ";)V", null, null);
                    mv.visitCode();
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitFieldInsn(PUTFIELD, innerClassName, fName, Util.TASK2_DESC);
                    mv.visitInsn(RETURN);
                    mv.visitMaxs(2, 2);
                    mv.visitEnd();
                }
            }
        }

        Type[] argumentTypes = Type.getArgumentTypes(methodNode.desc);

        int startOfLocal = (methodNode.access & ACC_STATIC) == ACC_STATIC ? 0 : 1;
        if (startOfLocal == 1) {
            fv = cw.visitField(ACC_PRIVATE + ACC_FINAL, "$outer_this", "L" + this.className + ";", null, null);
            fv.visitEnd();
        }

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < argumentTypes.length; ++i) {

            Type argumentType = argumentTypes[i];
            String argDesc = argumentType.getDescriptor();
            result.append(argDesc);
            String fName = "$arg_" + i;
            FieldVisitor field = cw.visitField(ACC_PRIVATE, fName, argDesc, null, null);
            field.visitEnd();

            //void $set$arg_0($arg_0,innerClassThis)
            MethodVisitor mv = cw.visitMethod(ACC_PRIVATE + ACC_STATIC, "$set" + fName, "(" + argDesc + "L" + innerClassName + ";)V", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(argumentType.getOpcode(ILOAD), 0);
            mv.visitFieldInsn(PUTFIELD, innerClassName, fName, argDesc);
            mv.visitInsn(RETURN);
            mv.visitMaxs(2, 2);
            mv.visitEnd();
        }

        String innerClassInitDesc = "(" + (startOfLocal == 0 ? "" : this.classDesc) + result.toString() + ")V";

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", innerClassInitDesc, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);

        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ICONST_0);
        mv.visitFieldInsn(PUTFIELD, innerClassName, "state", "I");

        if (startOfLocal == 1) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(PUTFIELD, innerClassName, "$outer_this", this.classDesc);
        }

        for (int i = 0, localIndex = startOfLocal + 1; i < argumentTypes.length; ++i) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(argumentTypes[i].getOpcode(ILOAD), localIndex);
            mv.visitFieldInsn(PUTFIELD, innerClassName, "$arg_" + i, argumentTypes[i].getDescriptor());
            localIndex += argumentTypes[i].getSize();
        }

        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, Util.STACK_INTERNAL_NAME, "getStack", "()" + Util.STACK_DESC, false);
        mv.visitFieldInsn(PUTFIELD, innerClassName, "$stack", Util.STACK_DESC);

        mv.visitInsn(RETURN);
        mv.visitMaxs(2, startOfLocal + argumentTypes.length + 1);
        mv.visitEnd();

        MethodVisitor originalMv = super.visitMethod(access, name, desc, signature, exceptions);
        MethodVisitor runMv = cw.visitMethod(ACC_PUBLIC, "run", "()V", null, null);
        AsyncMethodVisitor methodVisitor = new AsyncMethodVisitor(task1Count + task2Count + 1, innerClassName, this.className, this.classDesc, argumentTypes, runMv, innerClassInitDesc, originalMv, methodNode);
        byte[] innerClassByteCode = cv.toByteArray();
        save(internalName, innerClassByteCode);
        return methodVisitor;
    }

    private MethodVisitor processPrivateMethodCall(int access, String name, String desc, String signature, String[] exceptions) {
        if (!Modifier.isPrivate(access)) return super.visitMethod(access, name, desc, signature, exceptions);

        if (!callees.contains(new Callee(className, name, desc)))
            return super.visitMethod(access, name, desc, signature, exceptions);

        return super.visitMethod(Access.of(access).remove(Opcodes.ACC_PRIVATE).add(Opcodes.ACC_PUBLIC).get(), name, desc, signature, exceptions);
    }

    private String innerClassName(String currentClassName, String internalName) {
        return currentClassName + "$" + internalName;
    }

    private void save(String innerClassName, byte[] innerClassByteCode) {
        FileOutputStream os = null;
        try {
            os = new FileOutputStream(parentPath + "$" + innerClassName + ".class");
            os.write(innerClassByteCode);
            os.flush();
        } catch (IOException e) {

        } finally {
            try {
                if (os != null) os.close();
            } catch (IOException e) {

            }
        }
    }
}
