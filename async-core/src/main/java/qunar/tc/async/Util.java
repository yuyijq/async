package qunar.tc.async;

import qunar.tc.async.org.objectweb.asm.Opcodes;
import qunar.tc.async.org.objectweb.asm.Type;
import qunar.tc.async.org.objectweb.asm.tree.MethodInsnNode;

/**
 * Created by zhaohui.yu
 * 6/12/15
 */
public class Util {

    public static final String TASK1_DESC = Type.getDescriptor(Task1.class);
    public static final String TASK2_DESC = Type.getDescriptor(Task2.class);

    public static final String TASK1_INTERNAL_NAME = Type.getInternalName(Task1.class);
    public static final String TASK2_INTERNAL_NAME = Type.getInternalName(Task2.class);


    public static final String AWAITER_INTERNAL_NAME = Type.getInternalName(Awaiter.class);
    public static final String AWAIT1_METHOD_DESC = "(Lqunar/tc/async/Task1;)Ljava/lang/Object;";
    public static final String AWAIT2_METHOD_DESC = "(Lqunar/tc/async/Task2;)V";
    public static final String CALLBACK_METHOD_DESC = "(Ljava/lang/Runnable;)V";

    public static final String STACK_DESC = Type.getDescriptor(Stack.class);
    public static final String STACK_INTERNAL_NAME = Type.getInternalName(Stack.class);

    public static boolean isAwait(MethodInsnNode node) {
        return node.getOpcode() == Opcodes.INVOKESTATIC && AWAITER_INTERNAL_NAME.equals(node.owner) && "await".equals(node.name);
    }

    public static boolean isAwait1(MethodInsnNode node) {
        return isAwait(node) && AWAIT1_METHOD_DESC.equals(node.desc);
    }

    public static boolean isAwait2(MethodInsnNode node) {
        return isAwait(node) && AWAIT2_METHOD_DESC.equals(node.desc);
    }

    public static boolean isAwait(int opcode, String owner, String name) {
        return opcode == Opcodes.INVOKESTATIC
                && AWAITER_INTERNAL_NAME.equals(owner)
                && "await".equals(name);
    }
}
