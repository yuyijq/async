package qunar.tc.async;

/**
 * Created by zhaohui.yu
 * 16/4/28
 */
class Access {

    private int access;

    public static Access of(int access) {
        return new Access(access);
    }

    private Access(int access) {
        this.access = access;
    }

    public Access remove(int remove) {
        access &= ~remove;
        return this;
    }

    public Access add(int add) {
        access |= add;
        return this;
    }

    public boolean contain(int partAccess) {
        return (access & partAccess) != 0;
    }

    public int get() {
        return access;
    }
}
