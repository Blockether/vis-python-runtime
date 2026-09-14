import com.blockether.vispython.Interpreter;
import com.blockether.vispython.Native;
import java.util.List;

/** Run trusted Python, capture a block, and call a host function. */
public final class Example {
    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Pass the unpacked platform archive directory");
        }
        Native.use(args[0]);
        Interpreter.initialize(List.of(), Interpreter.DEFAULT, Interpreter.DEFAULT, null);
        String session = "example";
        try {
            Interpreter.exec(session, "values = [1, 2, 3]");
            System.out.println(Interpreter.eval(session, "sum(values)"));
            Interpreter.installRuntime(session);
            System.out.println(Interpreter.runBlock(session, "print('Hello from Python')"));
            Interpreter.bindHost((caller, name, payload) -> {
                if (!session.equals(caller) || !"greeting".equals(name)) {
                    return "{\"error\":\"Host call denied\"}";
                }
                return "{\"value\":\"Hello from Java\"}";
            });
            Interpreter.installSyncTool(session, "greeting");
            System.out.println(Interpreter.eval(session, "greeting()"));
        } finally {
            Interpreter.bindHost(null);
            Interpreter.closeSession(session);
        }
    }
}
