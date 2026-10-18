package thumbnailer.web.ws;

import org.springframework.stereotype.Component;
import thumbnailer.ops.FileOps;

/** Answers the file operation messages: preflight, run, cancel. */
@Component
class OpsApi {

    private final FileOps ops;

    OpsApi(FileOps ops) { this.ops = ops; }

    Event handle(Request.OpsRequest request) {
        return switch (request) {
            case Request.PreflightOp(var op) -> new Event.PreflightEvent(ops.preflight(required(op)));
            case Request.RunOp(var op) -> new Event.OpAccepted(ops.submit(required(op)));
            case Request.CancelOp c -> {
                ops.cancel();
                yield new Event.Ok(null);
            }
        };
    }

    private static <T> T required(T op) {
        if (op == null) throw new IllegalArgumentException("op is required");
        return op;
    }
}
