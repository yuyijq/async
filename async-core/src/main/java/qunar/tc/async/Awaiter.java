package qunar.tc.async;

/**
 * Created by zhaohui.yu
 * 6/8/15
 */
public class Awaiter {
    public static <T> T await(Task1<T> task) {
        return task.getResult();
    }

    public static void await(Task2 task) {
        task.getResult();
    }

    public static <T> Task1<T> ret(T result) {
        return null;
    }

    public static Task2 ret() {
        return null;
    }
}
