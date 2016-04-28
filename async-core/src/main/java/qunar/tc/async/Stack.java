package qunar.tc.async;

/**
 * Created by zhaohui.yu
 * 6/11/15
 */
public final class Stack {
    private static final int INITIAL_METHOD_STACK_DEPTH = 16;
    private static final int FRAME_RECORD_SIZE = 1;
    private int sp;
    private long[] dataLong;
    private Object[] dataObject;

    Stack(int stackSize) {
        if (stackSize <= 0)
            throw new IllegalArgumentException("stackSize");

        this.dataLong = new long[stackSize + (FRAME_RECORD_SIZE * INITIAL_METHOD_STACK_DEPTH)];
        this.dataObject = new Object[stackSize + (FRAME_RECORD_SIZE * INITIAL_METHOD_STACK_DEPTH)];

        resumeStack();
    }

    public static Stack getStack() {
        return new Stack(10);
    }

    public final void resumeStack() {
        sp = 0;
    }

    public static void push(int value, Stack s, int idx) {
        s.dataLong[s.sp + idx] = value;
    }

    public static void push(float value, Stack s, int idx) {
        s.dataLong[s.sp + idx] = Float.floatToRawIntBits(value);
    }

    public static void push(long value, Stack s, int idx) {
        s.dataLong[s.sp + idx] = value;
    }

    public static void push(double value, Stack s, int idx) {
        s.dataLong[s.sp + idx] = Double.doubleToRawLongBits(value);
    }

    public static void push(Object value, Stack s, int idx) {
        s.dataObject[s.sp + idx] = value;
    }

    public final int getInt(int idx) {
        return (int) dataLong[sp + idx];
    }

    public final float getFloat(int idx) {
        return Float.intBitsToFloat((int) dataLong[sp + idx]);
    }

    public final long getLong(int idx) {
        return dataLong[sp + idx];
    }

    public final double getDouble(int idx) {
        return Double.longBitsToDouble(dataLong[sp + idx]);
    }

    public final Object getObject(int idx) {
        return dataObject[sp + idx];
    }

    private static final long MASK_FULL = 0xffffffffffffffffL;

    private static long getUnsignedBits(long word, int offset, int length) {
        int a = 64 - length;
        int b = a - offset;
        return (word >>> b) & (MASK_FULL >>> a);
    }

    private static long getSignedBits(long word, int offset, int length) {
        int a = 64 - length;
        int b = a - offset;
        long xx = (word >>> b) & (MASK_FULL >>> a);
        return (xx << a) >> a;
    }

    private static long setBits(long word, int offset, int length, long value) {
        int a = 64 - length;
        int b = a - offset;
        word = word & ~((MASK_FULL >>> a) << b);
        word = word | (value << b);
        return word;
    }

    private static boolean getBit(long word, int offset) {
        return (getUnsignedBits(word, offset, 1) != 0);
    }

    private static long setBit(long word, int offset, boolean value) {
        return setBits(word, offset, 1, value ? 1 : 0);
    }
}
