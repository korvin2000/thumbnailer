package thumbnailer.web.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import thumbnailer.catalog.EntityKind;
import thumbnailer.ops.Op;
import thumbnailer.ops.Preflight;
import tools.jackson.databind.json.JsonMapper;

/** The facade groups are nested sealed interfaces: Jackson must find the type names through them. */
class RequestTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void typeNamesResolveThroughTheFacadeGroups() {
        assertThat(read("{\"type\":\"entities\",\"kind\":\"MODEL\",\"sort\":\"COUNT\",\"offset\":0,\"limit\":5}"))
                .isEqualTo(new Request.Entities(EntityKind.MODEL, null, null, null, Request.Entities.Sort.COUNT, 0, 5));
        assertThat(read("{\"type\":\"cancelOp\"}")).isInstanceOf(Request.OpsRequest.class);
        assertThat(read("{\"type\":\"markPhoto\",\"albumId\":\"a\",\"name\":\"x.jpg\",\"lists\":[3]}"))
                .isEqualTo(new Request.MarkPhoto("a", "x.jpg", null, List.of(3)));
        assertThat(read("{\"type\":\"search\"}")).isEqualTo(new Request.Search(null));
        assertThat(read("{\"type\":\"entities\",\"kind\":\"MODEL\",\"unused\":true,\"offset\":0,\"limit\":5}"))
                .isEqualTo(new Request.Entities(EntityKind.MODEL, null, null, true, null, 0, 5));
        assertThat(read("{\"type\":\"reveal\",\"albumId\":null,\"path\":\"C:/lib/A\"}")).isEqualTo(new Request.Reveal(null, "C:/lib/A"));
        assertThatThrownBy(() -> read("{\"type\":\"nope\"}")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void anOperationTakesItsPasswordButNeverShowsIt() {
        var op = ((Request.RunOp) read("{\"type\":\"op\",\"op\":{\"kind\":\"CRYPT\",\"encrypt\":true,\"password\":\"s3cret\"}}")).op();
        assertThat(op.password()).isEqualTo("s3cret");
        assertThat(op.conflict()).isEqualTo(Op.Conflict.SKIP);
        assertThat(json.writeValueAsString(op)).doesNotContain("s3cret").doesNotContain("password");
        assertThat(op.toString()).doesNotContain("s3cret");
        assertThat(json.writeValueAsString(new Event.Ok(null))).isEqualTo("{\"type\":\"ok\"}");
        assertThat(json.writeValueAsString(new Event.Ok(3))).isEqualTo("{\"type\":\"ok\",\"count\":3}");
        var preflight = new Preflight(List.of(), 0, -1, List.of(), List.of(), null);
        assertThat(json.writeValueAsString(new Event.PreflightEvent(preflight))).as("absent unless verified").doesNotContain("keyVerified");
        assertThat(json.writeValueAsString(new Event.PreflightEvent(new Preflight(List.of(), 0, -1, List.of(), List.of(), true))))
                .contains("\"keyVerified\":true");
    }

    private Request read(String text) { return json.readValue(text, Request.class); }
}
