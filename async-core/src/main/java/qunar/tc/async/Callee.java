package qunar.tc.async;

/**
 * Created by zhaohui.yu
 * 16/4/28
 */
public class Callee {
    public final String owner;

    public final String name;

    public final String desc;

    public Callee(String owner, String name, String desc) {
        this.owner = owner;
        this.name = name;
        this.desc = desc;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Callee callee = (Callee) o;

        if (desc != null ? !desc.equals(callee.desc) : callee.desc != null) return false;
        if (name != null ? !name.equals(callee.name) : callee.name != null) return false;
        if (owner != null ? !owner.equals(callee.owner) : callee.owner != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = owner != null ? owner.hashCode() : 0;
        result = 31 * result + (name != null ? name.hashCode() : 0);
        result = 31 * result + (desc != null ? desc.hashCode() : 0);
        return result;
    }
}
