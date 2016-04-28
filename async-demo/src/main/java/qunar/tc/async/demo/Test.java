package qunar.tc.async.demo;


import qunar.tc.async.AsyncMethod;
import qunar.tc.async.Awaiter;
import qunar.tc.async.Task1;

import java.io.IOException;

/**
 * Created by zhaohui.yu
 * 6/8/15
 */
public class Test {

    private String a = "a";

    @AsyncMethod
    public void test1(String a) {
        TTT ttt = new TTT();
        System.out.println("test1 start" + a + ttt.b);
        User user = new User();
        System.out.println("1: " + System.currentTimeMillis());
        String content = Awaiter.await(download("http://www.baidu.com"));
        System.out.println("2: " + System.currentTimeMillis());
        System.out.println(content);
        String url = process(content);
        System.out.println("3: " + System.currentTimeMillis());
        content = Awaiter.await(download(url));
        System.out.println("4: " + System.currentTimeMillis());
        System.out.println(content);
        System.out.println(user.getName());
        System.out.println(a);
    }

    public static String process(String content) {
        return content;
    }

    public void test() {
        test1("a");
    }


    public static Task1<String> download(String url) {
        final Task task = new Task(url);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                }
                task.set();
            }
        }).start();
        return task;
    }

    private class TTT {
        private String b = "test";
    }

    private static class Task implements Task1<String> {

        private volatile String result;

        private volatile Runnable continuation;

        public Task(String url) {
            this.result = url;
        }

        @Override
        public boolean isComplete() {
            return false;
        }

        @Override
        public void onComplete(Runnable continuation) {
            this.continuation = continuation;
        }

        @Override
        public String getResult() {
            return result;
        }

        public void set() {
            this.result += "  done ";
            this.continuation.run();
        }

    }

    public static void main(String[] args) throws IOException {
        Test test = new Test();
        test.test();

        System.in.read();
    }
}
