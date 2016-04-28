package qunar.tc.async;

/**
 * Created by zhaohui.yu
 * 6/8/15
 */
public interface Task1<T> {
    boolean isComplete();

    void onComplete(Runnable continuation);

    T getResult();
}
