package qunar.tc.async.main;

import qunar.tc.async.AsyncCheckClassVisitor;
import qunar.tc.async.AsyncClassVisitor;
import qunar.tc.async.org.objectweb.asm.ClassReader;
import qunar.tc.async.org.objectweb.asm.ClassWriter;

import java.io.*;

/**
 * Created by zhaohui.yu
 * 16/4/28
 */
public class Instruments {
    public static void main(String[] args) throws IOException {
        String path = args[0];
        FileInputStream stream = null;
        FileOutputStream os = null;
        try {
            stream = new FileInputStream(path);
            byte[] code = instrument(path, stream);
            if (code == null) return;
            os = new FileOutputStream(path);
            os.write(code);
        } finally {
            close(os);
            close(stream);
        }
    }

    private static byte[] instrument(String path, InputStream is) throws IOException {
        ClassReader reader = new ClassReader(is);
        AsyncCheckClassVisitor checkClassVisitor = new AsyncCheckClassVisitor();
        reader.accept(checkClassVisitor, 0);


        FileInputStream stream = null;
        try {
            stream = new FileInputStream(path);
            reader = new ClassReader(stream);
            ClassWriter cw = new ClassWriter(0);
            AsyncClassVisitor classVisitor = new AsyncClassVisitor(path, cw, checkClassVisitor.getAsyncMethods(), checkClassVisitor.getCallees(), checkClassVisitor.getFieldAccesses());
            reader.accept(classVisitor, 0);
            return cw.toByteArray();
        } finally {
            close(stream);
        }
    }

    private static void close(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException e) {
            //ignore
        }
    }
}
