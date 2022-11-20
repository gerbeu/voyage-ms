package ibm.eda.kc.voyagems.infra.events.voyage;

import java.text.MessageFormat;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import javax.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import ibm.eda.kc.voyagems.infra.events.order.OrderEvent;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.context.Context;
import io.smallrye.reactive.messaging.TracingMetadata;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;

import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;

import static io.smallrye.reactive.messaging.kafka.KafkaConnector.TRACER;

@ApplicationScoped
public class VoyageEventProducer {
    Logger logger = Logger.getLogger(VoyageEventProducer.class.getName());

    @Channel("voyages")
    public Emitter<VoyageEvent> eventProducer;


    public void sendEvent(String key, VoyageEvent voyageEvent) {
        logger.info("Send voyage message --> " + voyageEvent.voyageID + " ts: " + voyageEvent.getTimestampMillis());
        Context context = Context.current();
        createVoyageEventProducedSpan(voyageEvent, context);
        eventProducer.send(Message.of(voyageEvent)
                .addMetadata(OutgoingKafkaRecordMetadata.<String>builder().withKey(key).build())
                .withAck(() -> {
                    return CompletableFuture.completedFuture(null);
                })
                .withNack(throwable -> {
                    return CompletableFuture.completedFuture(null);
                })
                .addMetadata(TracingMetadata.withCurrent(context))
        );
    }

    private void createVoyageEventProducedSpan(final VoyageEvent voyageEvent, final Context context) {
        final String spanName = MessageFormat.format("produced event[{0}]", voyageEvent.getType());
        final SpanBuilder spanBuilder = TRACER.spanBuilder(spanName).setParent(context);
        final Span span = spanBuilder.startSpan();
        final ObjectWriter ow = new ObjectMapper().writer().withDefaultPrettyPrinter();
        try {
            final String voyageEventJson = ow.writeValueAsString(voyageEvent);
            span.setAttribute("produced.event", voyageEventJson);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        } finally {
            span.end();
        }
    }

}
