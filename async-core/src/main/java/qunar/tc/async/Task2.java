package qunar.tc.async;

/**
 * Created by zhaohui.yu
 * 6/8/15
 */
public interface Task2 {
    boolean isComplete();

    void onComplete(Runnable continuation);

    void getResult();
}
