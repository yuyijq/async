package qunar.tc.async;

import qunar.tc.async.org.objectweb.asm.*;
import qunar.tc.async.org.objectweb.asm.tree.*;
import qunar.tc.async.org.objectweb.asm.tree.analysis.Analyzer;
import qunar.tc.async.org.objectweb.asm.tree.analysis.AnalyzerException;
import qunar.tc.async.org.objectweb.asm.tree.analysis.BasicValue;
import qunar.tc.async.org.objectweb.asm.tree.analysis.Frame;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by zhaohui.yu
 * 6/8/15
 */
public class AsyncMethodVisitor extends MethodVisitor implements Opcodes {
    private static final Map<String, String> desc2owner = new HashMap<String, String>();

    static {
        desc2owner.put(Util.TASK1_DESC, Util.TASK1_INTERNAL_NAME);
        desc2owner.put(Util.TASK2_DESC, Util.TASK2_INTERNAL_NAME);
    }

    private int index;

    private final String owner;
    private Type[] args;
    private String innerClassInitDesc;

    private final Label[] labels;

    private final int firstLocal;

    private Map<Integer, LabelNode> beforeAsyncLabels;
    private Map<Integer, LabelNode> afterAsyncLabels;
    private boolean isStatic;

    private Map<Integer, Integer> localIndex2ArgIndex;

    public AsyncMethodVisitor(int count, String owner, String outerClassName, String outerDesc, Type[] args, MethodVisitor runMv, String innerClassInitDesc, MethodVisitor originalMethod, MethodNode methodNode) {
        super(ASM5, originalMethod);
        this.owner = owner;
        this.args = args;
        this.isStatic = (ACC_STATIC & methodNode.access) == ACC_STATIC;
        //实例方法的第一个变量从1开始, 0是this
        this.firstLocal = isStatic ? 0 : 1;

        int argsSize = computeArgsSize(firstLocal, args);

        this.innerClassInitDesc = innerClassInitDesc;
        int maxStack = methodNode.maxStack;
        //原来的参数全部变成了字段，原来如果是静态方法，则多一个this
        int maxLocals = methodNode.maxLocals - args.length + 1 - firstLocal;

        this.beforeAsyncLabels = new HashMap<Integer, LabelNode>();
        this.afterAsyncLabels = new HashMap<Integer, LabelNode>();


        labels = new Label[count + 1];

        Analyzer analyzer = new Analyzer(new TypedInterpreter());
        Frame[] frames;
        try {
            frames = analyzer.analyze(outerClassName, methodNode);
        } catch (AnalyzerException e) {
            throw new RuntimeException("compute frames failed");
        }

        processTryCatchBlocks(methodNode);

        runMv.visitCode();
        attachTryCatchBlocks(runMv, methodNode);
        runMv.visitVarInsn(ALOAD, 0);
        runMv.visitFieldInsn(GETFIELD, owner, "state", "I");

        for (int i = 0; i < count + 1; ++i) {
            labels[i] = new Label();
        }
        int[] states = new int[count];
        for (int i = 0; i < count; ++i) {
            states[i] = i;
        }
        Label[] beginOfBlocks = new Label[count];
        System.arraycopy(labels, 0, beginOfBlocks, 0, count);
        runMv.visitLookupSwitchInsn(labels[labels.length - 1], states, beginOfBlocks);
        runMv.visitLabel(labels[0]);

        InsnList insnList = methodNode.instructions;
        for (int i = 0; i < insnList.size(); ++i) {
            AbstractInsnNode insnNode = insnList.get(i);

            if (insnNode instanceof VarInsnNode) {
                VarInsnNode varInsnNode = (VarInsnNode) insnNode;

                //原先访问this的，全部变成访问$outer_this字段
                final int var = varInsnNode.var;
                if (varInsnNode.getOpcode() == ALOAD && var == 0 && !isStatic) {
                    runMv.visitVarInsn(ALOAD, 0);
                    runMv.visitFieldInsn(GETFIELD, owner, "$outer_this", outerDesc);
                    continue;
                }

                if (varInsnNode.getOpcode() >= ILOAD && varInsnNode.getOpcode() <= SALOAD) {
                    //将原来对参数的访问变成对参数的访问
                    if (var < argsSize + firstLocal) {
                        int index = localIndex2ArgIndex.get(var);
                        runMv.visitVarInsn(ALOAD, 0);
                        runMv.visitFieldInsn(GETFIELD, this.owner, "$arg_" + index, args[index].getDescriptor());
                    } else {
                        runMv.visitVarInsn(varInsnNode.getOpcode(), var - argsSize + (1 - firstLocal));
                    }
                    continue;
                }

                if (varInsnNode.getOpcode() >= ISTORE && varInsnNode.getOpcode() <= SASTORE) {
                    if (var < argsSize + firstLocal) {
                        int index = localIndex2ArgIndex.get(var);
                        runMv.visitVarInsn(ALOAD, 0);
                        runMv.visitMethodInsn(INVOKESTATIC, this.owner, "$set$arg_" + index, "(" + args[index].getDescriptor() + "L" + this.owner + ";)V", false);
                    } else {
                        runMv.visitVarInsn(varInsnNode.getOpcode(), var - argsSize + (1 - firstLocal));
                    }
                    continue;
                }
            }

            if (insnNode instanceof MethodInsnNode) {
                MethodInsnNode methodInsnNode = (MethodInsnNode) insnNode;
                if (Util.isAwait(methodInsnNode)) {
                    Frame frame = frames[i];
                    if (frame == null) {
                        throw new RuntimeException("");
                    }

                    String taskName;
                    String taskDesc;
                    if (methodInsnNode.desc.equals(Util.AWAIT1_METHOD_DESC)) {
                        taskName = "$task1_" + index;
                        taskDesc = Util.TASK1_DESC;
                    } else {
                        taskName = "$task2_" + index;
                        taskDesc = Util.TASK2_DESC;
                    }

                    int[] localIndex = new int[frame.getLocals()];
                    int[] stackIndex = new int[frame.getStackSize()];

                    int objectIndex = 0;
                    int priIndex = 0;
                    for (int l = 0; l < frame.getLocals(); ++l) {
                        BasicValue local = (BasicValue) frame.getLocal(l);
                        if (local.isReference()) {
                            localIndex[l] = objectIndex++;
                        } else {
                            localIndex[l] = priIndex++;
                        }
                    }
                    for (int s = 0; s < frame.getStackSize(); ++s) {
                        BasicValue value = (BasicValue) frame.getStack(s);
                        if (value.isReference()) {
                            stackIndex[s] = objectIndex++;
                        } else {
                            stackIndex[s] = priIndex++;
                        }
                    }

                    saveFrame(runMv, taskDesc, taskName, frame, localIndex, stackIndex);

                    LabelNode beforeLabel = beforeAsyncLabels.get(i);
                    if (beforeLabel != null) {
                        beforeLabel.accept(runMv);
                    }
                    //跳到swtich的默认handler
                    runMv.visitJumpInsn(GOTO, labels[labels.length - 1]);
                    restoreFrame(i, runMv, taskDesc, taskName, frame, localIndex, stackIndex);
                }
            }

            if (insnNode.getOpcode() == Opcodes.RETURN) {
                runMv.visitLabel(labels[labels.length - 1]);
            }
            insnNode.accept(runMv);
        }


        runMv.visitMaxs(maxStack + 6, maxLocals);
        runMv.visitEnd();
    }

    private int computeArgsSize(int firstLocal, Type[] args) {
        localIndex2ArgIndex = new HashMap<Integer, Integer>();
        int size = 0;
        for (int i = 0, localOffset = firstLocal; i < args.length; ++i) {
            localIndex2ArgIndex.put(localOffset, i);
            size += args[i].getSize();
            localOffset += args[i].getSize();
        }
        return size;
    }

    private void attachTryCatchBlocks(MethodVisitor runMv, MethodNode methodNode) {
        for (int i = 0; i < methodNode.tryCatchBlocks.size(); ++i) {
            methodNode.tryCatchBlocks.get(i).accept(runMv);
        }
    }

    private void processTryCatchBlocks(MethodNode methodNode) {
        InsnList instructions = methodNode.instructions;
        for (int i = 0; i < instructions.size(); ++i) {
            AbstractInsnNode insnNode = instructions.get(i);
            if (insnNode instanceof MethodInsnNode) {
                if (Util.isAwait((MethodInsnNode) insnNode)) {
                    processTryCatchBlocks(i, methodNode);
                }
            }
        }
    }

    private void processTryCatchBlocks(int frameIndex, MethodNode methodNode) {
        for (int i = 0; i < methodNode.tryCatchBlocks.size(); ++i) {
            TryCatchBlockNode tryCatchBlockNode = methodNode.tryCatchBlocks.get(i);

            int start = getLabelIndex(methodNode, tryCatchBlockNode.start);
            int end = getLabelIndex(methodNode, tryCatchBlockNode.end);

            //这个try catch将异步方法包裹起来了
            if (start <= frameIndex && end >= frameIndex) {
                //try在异步方法的前一行
                if (start == frameIndex) {
                    //将try挪动到异步方法调用之后的一行
                    LabelNode label = new IndexableLabelNode(frameIndex);
                    afterAsyncLabels.put(frameIndex, label);
                    tryCatchBlockNode.start = label;
                } else {
                    //将异步方法包起来了
                    //则将try catch拆分成两个try catch
                    if (end > frameIndex) {
                        LabelNode label = new IndexableLabelNode(frameIndex);
                        afterAsyncLabels.put(frameIndex, label);
                        TryCatchBlockNode tryCatchBlockNode2 = new TryCatchBlockNode(label, tryCatchBlockNode.end, tryCatchBlockNode.handler, tryCatchBlockNode.type);
                        methodNode.tryCatchBlocks.add(i + 1, tryCatchBlockNode2);
                    }

                    LabelNode label = new IndexableLabelNode(frameIndex);
                    beforeAsyncLabels.put(frameIndex, label);
                    tryCatchBlockNode.end = label;
                }
            }
        }
    }

    private int getLabelIndex(MethodNode methodNode, LabelNode label) {
        if (label instanceof IndexableLabelNode) {
            return ((IndexableLabelNode) label).index;
        }
        return methodNode.instructions.indexOf(label);
    }

    //记录下设置的位置，避免下次切分的时候因为位置还没确定又切分一次
    private static class IndexableLabelNode extends LabelNode {
        public final int index;

        public IndexableLabelNode(int index) {
            this.index = index;
        }
    }

    private void saveFrame(MethodVisitor runMv, String desc, String taskName, Frame frame, int[] localIndex, int[] stackIndex) {
        //将调用后的task保存到字段中
        runMv.visitVarInsn(ALOAD, 0);
        runMv.visitMethodInsn(INVOKESTATIC, this.owner, "$set" + taskName, "(" + desc + "L" + owner + ";)V", false);

        //设置状态机下个状态
        runMv.visitVarInsn(ALOAD, 0);
        setConst(runMv, ++index);
        runMv.visitFieldInsn(PUTFIELD, owner, "state", "I");

        //在这里保存现场，在最后保存现场可能会导致恢复现场的时候，现场还没保存呢
        saveStack(runMv, frame, stackIndex);
        saveLocals(runMv, frame, localIndex);

        //在task上注册callback
        runMv.visitVarInsn(ALOAD, 0);
        runMv.visitFieldInsn(GETFIELD, owner, taskName, desc);
        runMv.visitVarInsn(ALOAD, 0);
        runMv.visitMethodInsn(INVOKEINTERFACE, desc2owner.get(desc), "onComplete", Util.CALLBACK_METHOD_DESC, true);
    }

    private void saveLocals(MethodVisitor runMv, Frame frame, int[] localIndex) {
        int locals = frame.getLocals();
        //如果是静态方法则从0开始，否则从1开始
        for (int i = firstLocal + args.length, offsetOfLocal = 1; i < locals; ++i) {
            BasicValue local = (BasicValue) frame.getLocal(i);
            if (!isNullType(local)) {
                runMv.visitVarInsn(local.getType().getOpcode(ILOAD), offsetOfLocal);
                pushToStack(runMv, local, localIndex[i]);
                offsetOfLocal += local.getSize();
            }
        }
    }

    private void pushToStack(MethodVisitor runMv, BasicValue value, int idx) {
        String desc;

        switch (value.getType().getSort()) {
            case Type.OBJECT:
            case Type.ARRAY:
                desc = "(Ljava/lang/Object;" + Util.STACK_DESC + "I)V";
                break;
            case Type.BOOLEAN:
            case Type.BYTE:
            case Type.SHORT:
            case Type.CHAR:
            case Type.INT:
                desc = "(I" + Util.STACK_DESC + "I)V";
                break;
            case Type.FLOAT:
                desc = "(F" + Util.STACK_DESC + "I)V";
                break;
            case Type.LONG:
                desc = "(J" + Util.STACK_DESC + "I)V";
                break;
            case Type.DOUBLE:
                desc = "(D" + Util.STACK_DESC + "I)V";
                break;
            default:
                throw new InternalError("Unexpected type: " + value.getType());
        }

        runMv.visitVarInsn(ALOAD, 0);
        runMv.visitFieldInsn(GETFIELD, owner, "$stack", Util.STACK_DESC);
        setConst(runMv, idx);
        runMv.visitMethodInsn(Opcodes.INVOKESTATIC, Util.STACK_INTERNAL_NAME, "push", desc, false);
    }

    private void saveStack(MethodVisitor runMv, Frame frame, int[] stackIndex) {
        int stackSize = frame.getStackSize();
        //栈顶上是task，保存到field里了，不保存
        for (int i = stackSize - 1; i > 0; --i) {
            BasicValue v = (BasicValue) frame.getStack(i);
            if (!isNullType(v)) {
                pushToStack(runMv, v, stackIndex[i]);
            } else {
                runMv.visitInsn(Opcodes.POP);
            }
        }
    }

    static boolean isNullType(BasicValue v) {
        return (v == BasicValue.UNINITIALIZED_VALUE)
                || (v.isReference() && v.getType().getInternalName().equals("null"));
    }

    private void restoreFrame(int insnIndex, MethodVisitor runMv, String desc, String taskName, Frame frame, int[] localIndex, int[] stackIndex) {
        runMv.visitLabel(labels[index]);
        restoreLocal(runMv, frame, localIndex);
        restoreStack(runMv, frame, stackIndex);
        resumeStack(runMv);
        LabelNode afterLabel = afterAsyncLabels.get(insnIndex);
        if (afterLabel != null) {
            afterLabel.accept(runMv);
        }
        runMv.visitVarInsn(ALOAD, 0);
        runMv.visitFieldInsn(GETFIELD, owner, taskName, desc);
    }

    private void resumeStack(MethodVisitor runMv) {
        runMv.visitVarInsn(ALOAD, 0);
        runMv.visitFieldInsn(GETFIELD, owner, "$stack", Util.STACK_DESC);
        runMv.visitMethodInsn(INVOKEVIRTUAL, Util.STACK_INTERNAL_NAME, "resumeStack", "()V", false);
    }

    private void restoreStack(MethodVisitor runMv, Frame frame, int[] stackIndex) {
        for (int i = 0; i < frame.getStackSize() - 1; ++i) {
            BasicValue v = (BasicValue) frame.getStack(i);
            if (!isNullType(v)) {
                int idx = stackIndex[i];
                restoreFromStack(runMv, v, idx);
            } else
                runMv.visitInsn(Opcodes.ACONST_NULL);
        }
    }

    private void restoreLocal(MethodVisitor runMv, Frame frame, int[] localIndex) {
        for (int i = firstLocal + args.length, offsetOfLocal = 1; i < frame.getLocals(); i++) {
            BasicValue v = (BasicValue) frame.getLocal(i);
            if (!isNullType(v)) {
                int idx = localIndex[i];
                restoreFromStack(runMv, v, idx);
                runMv.visitVarInsn(v.getType().getOpcode(Opcodes.ISTORE), offsetOfLocal);
            } else if (v != BasicValue.UNINITIALIZED_VALUE) {
                runMv.visitInsn(Opcodes.ACONST_NULL);
                runMv.visitVarInsn(Opcodes.ASTORE, offsetOfLocal);
            }
            offsetOfLocal += v.getSize();
        }
    }

    private void restoreFromStack(MethodVisitor runMv, BasicValue v, int idx) {
        runMv.visitVarInsn(ALOAD, 0);
        runMv.visitFieldInsn(GETFIELD, owner, "$stack", Util.STACK_DESC);
        setConst(runMv, idx);

        switch (v.getType().getSort()) {
            case Type.OBJECT:
                String internalName = v.getType().getInternalName();
                runMv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Util.STACK_INTERNAL_NAME, "getObject", "(I)Ljava/lang/Object;", false);
                if (!internalName.equals("java/lang/Object"))
                    runMv.visitTypeInsn(Opcodes.CHECKCAST, internalName);
                break;
            case Type.ARRAY:
                runMv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Util.STACK_INTERNAL_NAME, "getObject", "(I)Ljava/lang/Object;", false);
                runMv.visitTypeInsn(Opcodes.CHECKCAST, v.getType().getDescriptor());
                break;
            case Type.BYTE:
                runMv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Util.STACK_INTERNAL_NAME, "getInt", "(I)I", false);
                runMv.visitInsn(Opcodes.I2B);
                break;
            case Type.SHORT:
                runMv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Util.STACK_INTERNAL_NAME, "getInt", "(I)I", false);
                runMv.visitInsn(Opcodes.I2S);
                break;
            case Type.CHAR:
                runMv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Util.STACK_INTERNAL_NAME, "getInt", "(I)I", false);
                runMv.visitInsn(Opcodes.I2C);
                break;
            case Type.BOOLEAN:
            case Type.INT:
                runMv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Util.STACK_INTERNAL_NAME, "getInt", "(I)I", false);
                break;
            case Type.FLOAT:
                runMv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Util.STACK_INTERNAL_NAME, "getFloat", "(I)F", false);
                break;
            case Type.LONG:
                runMv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Util.STACK_INTERNAL_NAME, "getLong", "(I)J", false);
                break;
            case Type.DOUBLE:
                runMv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Util.STACK_INTERNAL_NAME, "getDouble", "(I)D", false);
                break;
            default:
                throw new InternalError("Unexpected type: " + v.getType());
        }
    }

    private void setConst(MethodVisitor runMv, int value) {
        if (value >= -1 && value <= 5)
            runMv.visitInsn(Opcodes.ICONST_0 + value);
        else if ((byte) value == value)
            runMv.visitIntInsn(Opcodes.BIPUSH, value);
        else if ((short) value == value)
            runMv.visitIntInsn(Opcodes.SIPUSH, value);
        else
            runMv.visitLdcInsn(value);
    }

    @Override
    public void visitCode() {
        super.visitCode();
        super.visitTypeInsn(NEW, owner);
        super.visitInsn(DUP);
        if (!isStatic) {
            super.visitVarInsn(ALOAD, 0);
        }
        for (int i = 0, localIndex = firstLocal; i < args.length; ++i) {
            super.visitVarInsn(args[i].getOpcode(ILOAD), localIndex);
            localIndex += args[i].getSize();
        }
        super.visitMethodInsn(INVOKESPECIAL, owner, "<init>", this.innerClassInitDesc, false);
        super.visitMethodInsn(INVOKEVIRTUAL, owner, "run", "()V", false);
        super.visitInsn(RETURN);
    }

    @Override
    public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
    }

    @Override
    public void visitInsn(int opcode) {
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc) {
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
    }

    @Override
    public void visitLabel(Label label) {
    }

    @Override
    public void visitLdcInsn(Object cst) {
    }

    @Override
    public void visitIincInsn(int var, int increment) {
    }

    @Override
    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
    }

    @Override
    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
    }

    @Override
    public void visitMultiANewArrayInsn(String desc, int dims) {
    }

    @Override
    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
    }

    @Override
    public AnnotationVisitor visitTryCatchAnnotation(int typeRef, TypePath typePath, String desc, boolean visible) {
        return null;
    }

    @Override
    public void visitLocalVariable(String name, String desc, String signature, Label start, Label end, int index) {
    }

    @Override
    public void visitLineNumber(int line, Label start) {
        super.visitLineNumber(line, start);
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        super.visitMaxs(2 + firstLocal + args.length, firstLocal + args.length);
    }

    @Override
    public AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath typePath, Label[] start, Label[] end, int[] index, String desc, boolean visible) {
        return null;
    }
}
