package qunar.tc.async;

import qunar.tc.async.org.objectweb.asm.Type;
import qunar.tc.async.org.objectweb.asm.tree.analysis.BasicInterpreter;
import qunar.tc.async.org.objectweb.asm.tree.analysis.BasicValue;

/**
 * Created by zhaohui.yu
 * 6/13/15
 */
public class TypedInterpreter extends BasicInterpreter {
    @Override
    public BasicValue newValue(Type type) {
        if (type == null)
            return BasicValue.UNINITIALIZED_VALUE;

        if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY)
            return new BasicValue(type);

        return super.newValue(type);
    }
}
