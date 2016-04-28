package qunar.tc.async;

import qunar.tc.async.org.objectweb.asm.*;
import qunar.tc.async.org.objectweb.asm.tree.MethodNode;

import java.util.*;

/**
 * Created by zhaohui.yu
 * 6/11/15
 */
public class AsyncCheckClassVisitor extends ClassVisitor {
    private static final String ASYNC_DESC = Type.getDescriptor(AsyncMethod.class);

    private final List<MethodNode> methods;

    private final Set<Callee> callees;

    private final Set<FieldAccess> fieldAccesses;

    private String className;

    public AsyncCheckClassVisitor() {
        super(Opcodes.ASM5);
        this.methods = new ArrayList<MethodNode>();
        this.callees = new HashSet<Callee>();
        this.fieldAccesses = new HashSet<FieldAccess>();
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        this.className = name;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        if (!checkAccess(access)) {
            return super.visitMethod(access, name, desc, signature, exceptions);
        }
        final MethodNode mn = new MethodNode(access, name, desc, signature, exceptions);
        return new MethodVisitor(Opcodes.ASM5, mn) {
            private boolean async = false;
            private boolean hasAwait = false;
            private boolean added = false;

            /**
             * 标记有@AsyncMethod的方法
             * @param desc
             *            the class descriptor of the annotation class.
             * @param visible
             *            <tt>true</tt> if the annotation is visible at runtime.
             * @return
             */
            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                if (ASYNC_DESC.equals(desc)) {
                    async = true;
                }
                return super.visitAnnotation(desc, visible);
            }

            /**
             * 方法里调用了Awaiter.await
             * 对于这样的方法才进行重排
             */
            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                if (async) {
                    if (Util.isAwait(opcode, owner, name)) {
                        hasAwait = true;
                    } else if (!itf && owner.equals(className)) {
                        callees.add(new Callee(owner, name, desc));
                    }
                }
                super.visitMethodInsn(opcode, owner, name, desc, itf);
            }

            @Override
            public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                if (owner.equals(className))
                    fieldAccesses.add(new FieldAccess(owner, name, desc));

                super.visitFieldInsn(opcode, owner, name, desc);
            }

            @Override
            public void visitEnd() {
                super.visitEnd();
                if (added) return;
                added = true;
                if (async && hasAwait) {
                    methods.add(mn);
                }
            }
        };
    }

    private boolean checkAccess(int access) {
        return (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
    }

    public List<MethodNode> getAsyncMethods() {
        return Collections.unmodifiableList(methods);
    }

    public Set<Callee> getCallees() {
        return Collections.unmodifiableSet(callees);
    }

    public Set<FieldAccess> getFieldAccesses() {
        return Collections.unmodifiableSet(fieldAccesses);
    }
}
