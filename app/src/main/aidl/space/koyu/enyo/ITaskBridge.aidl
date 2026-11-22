package space.koyu.enyo;

interface ITaskBridge {
    void destroy() = 16777114;
    String listTasks() = 1;
    boolean closeTask(int id, String component) = 2;
}
