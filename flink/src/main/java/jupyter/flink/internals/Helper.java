package jupyter.flink.internals;

import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.ExecutionEnvironmentFactory;

public abstract class Helper extends ExecutionEnvironment {
    public static void initializeContextEnvironment0(ExecutionEnvironmentFactory ctx) {
        ExecutionEnvironment.initializeContextEnvironment(ctx);
    }
}
