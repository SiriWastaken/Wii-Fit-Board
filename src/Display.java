/** Where the weighing engine shows its state. Called from the engine thread; must not block. */
interface Display {

    void update(Snapshot snapshot);
}
