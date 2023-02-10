package ibm.eda.kc.voyagems.infra.events.voyage;

import java.text.MessageFormat;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import javax.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
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
        sendEventWithContext(key, voyageEvent, context);
    }


    private void sendEventWithContext(String key, VoyageEvent voyageEvent, Context context) {
        final Span span = startSpan(voyageEvent, context);
        try {
            final String voyageEventJson = serializeVoyageEvent(voyageEvent);
            addEventAttributeToSpan(span, voyageEventJson);
            sendVoyageEventWithSpanContext(key, voyageEvent, span);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        } finally {
            endSpan(span);
        }
    }


    private Span startSpan(VoyageEvent voyageEvent, Context context) {
        final String spanName = formatSpanName(voyageEvent);
        final SpanBuilder spanBuilder = TRACER.spanBuilder(spanName).setParent(context);
        return spanBuilder.startSpan();
    }

    private String formatSpanName(VoyageEvent voyageEvent) {
        return MessageFormat.format("produced event[{0}]", voyageEvent.getType());
    }

    private String serializeVoyageEvent(VoyageEvent voyageEvent) throws JsonProcessingException {
        final ObjectWriter ow = new ObjectMapper().writer().withDefaultPrettyPrinter();
        return ow.writeValueAsString(voyageEvent);
    }

    private void addEventAttributeToSpan(Span span, String voyageEventJson) {
        span.setAttribute("produced.event", voyageEventJson);
    }

    private void sendVoyageEventWithSpanContext(String key, VoyageEvent voyageEvent, Span span) {
        final Context spanContext = Context.current().with(span);
        try (Scope scope = spanContext.makeCurrent()) {
            eventProducer.send(Message.of(voyageEvent)
                    .addMetadata(OutgoingKafkaRecordMetadata.<String>builder().withKey(key).build())
                    .withAck(() -> CompletableFuture.completedFuture(null))
                    .withNack(throwable -> CompletableFuture.completedFuture(null))
                    .addMetadata(TracingMetadata.withCurrent(spanContext))
            );
        }
    }

    private void endSpan(Span span) {
        span.end();
    }

}
