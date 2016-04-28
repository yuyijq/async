# 模仿.NET的async/await的异步实现

包含两部分内容

## 给Dubbo生成异步接口

给定如下API，标记上@Async
```
@Async
public interface UserService{
    User findUser(String name);
}
```
会生成
```
public interface UserServiceAsync implements UserService{
    ListenableFuture<User> findUserAsync(String name);
}
```
这样consumer调用的时候可以直接使用 UserServiceAsync调用findUserAsync，然后Dubbo内部转换成对原有方法的异步调用来降低Dubbo异步使用的难度。

## 通用的异步处理

### 传统异步
传统的异步方式往往使用callback，callback会使逻辑变得复杂，代码变得支离破碎。处理循环时也很不方便，比如：
```
        final User user = new User("aaa");
        final Task1<String> download1 = download("http://www.baidu.com");
        download1.onComplete(new Runnable() {
            @Override
            public void run() {
                System.out.println(user.name);
                String result = download1.getResult();
                final Task1<String> download2 = download(result);
                download2.onComplete(new Runnable() {
                    @Override
                    public void run() {
                        System.out.println(download2.getResult());
                    }
                });
            }
        });
```

### 转换状态机
那么我们把这个代码里的callback给去掉，变成顺序的：
```
        A
        final User user = new User("aaa");
        final Task1<String> download1 = download("http://www.baidu.com");
        B
        System.out.println(user.name);
        String result = download1.getResult();
        final Task1<String> download2 = download(result);
        C
        System.out.println(download2.getResult());
```
我们会发现，对于download1来讲 从B位置开始到结束位置，即为这个download1的回调，而对于download2来讲，从C位置开始到结束即为download2的回调。
那我们只需要实现一种机制，不断地循环执行上面的代码，每次进入的时候根据一个状态判断从哪个位置开始执行。
比如第一次执行的时候从A开始执行，执行到B的时候退出，第二次进来的时候从B开始执行。
这个其实就是一个状态机，每次执行的时候根据当前状态从某个位置开始执行。那么我们可以将上面的代码转换成一个swtich case就可以实现我们的目标了：
```
swtich(state){
  case 0:
     final User user = new User("aaa");
     final Task1<String> download1 = download("http://www.baidu.com");
     state = 1;
     download1.onComplete(this);
  case 1:
     System.out.println(user.name);
     String result = download1.getResult();
     final Task1<String> download2 = download(result);
     state = 2;
     download2.onComplete(this);
  case 2:
     System.out.println(download2.getResult());
}
```
那现在的问题就是如何将之前的代码转换成状态机。那么首先，我们要找到从哪里开始切断，作为状态的转换点。也就是需要一个标识。
将代码改写为下面的形式：
```
        final User user = new User("aaa");
        String result = Awaiter.await(download("http://www.baidu.com"));
        System.out.println(user.name);
        result = Awaiter.await(download(result));
        System.out.println(result);
```
而Awaiter.await是我提供的一个简单方法，主要做打标的作用。然后使用java的字节码工具ASM，扫描生成后的字节码，当碰到Awaiter.await的时候即对字节码
进行改写生成上面的状态机。

### 业务代码
最后，如果我们有一个方法里要调用异步，我们又不想使用异步，则使用方式可以像下面的代码这样：
```
    @AsyncMethod
    public void test(String url) {
        System.out.println("test1 start");
        User user = new User("aaa");
        String content = Awaiter.await(download(url));
        System.out.println(content);
        String url = process(content);
        content = Awaiter.await(download(url));
        System.out.println(content);
        System.out.println(user.name);
    }
```
我的处理方式是，给每个标记有AsyncMethod的方法生成一个内部类，这个内部类实现了Runnable，将这个test方法的代码移动到run方法里转换成状态机。
而test方法内部变成
```
    public void test(String url) {
        new InnerClass(url).run();
    }
```
主要代码在AsyncClassVisitor和AsyncMethodVisitor里

TODO LIST
* 如果test方法里有对原有类的私有成员访问，需要生成桥接成员(已完成)
* 需要处理ThreadLocal，在状态切换位置保存ThreadLocal，切换之后恢复
* 需要考虑回调在哪个上下文里执行，比如引入TaskScheduler机制
* 如果这个test方法本身也是异步的，我已经在Awaiter里加入了 Awaiter.ret方法，需要进一步考虑实现
* 进行详细的测试
* 写一个maven插件