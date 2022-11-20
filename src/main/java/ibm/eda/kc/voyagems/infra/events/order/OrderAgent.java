package ibm.eda.kc.voyagems.infra.events.order;

import java.text.MessageFormat;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.smallrye.reactive.messaging.TracingMetadata;
import org.eclipse.microprofile.reactive.messaging.*;

import ibm.eda.kc.voyagems.domain.Voyage;
import ibm.eda.kc.voyagems.infra.events.voyage.VoyageAllocated;
import ibm.eda.kc.voyagems.infra.events.voyage.VoyageEvent;
import ibm.eda.kc.voyagems.infra.events.voyage.VoyageEventProducer;
import ibm.eda.kc.voyagems.infra.repo.VoyageRepository;

import static io.smallrye.reactive.messaging.kafka.KafkaConnector.TRACER;

/**
 * Listen to the orders topic and processes event from order service:
 * - order created event
 * - order cancelled event
 * Normally it should also support order updated event and recompute capacity
 */
@ApplicationScoped
public class OrderAgent {
    Logger logger = Logger.getLogger(OrderAgent.class.getName());

    @Inject
    VoyageRepository repo;

    @Inject
    VoyageEventProducer voyageEventProducer;

    @Incoming("orders")
    public CompletionStage<Void> processOrder(Message<OrderEvent> messageWithOrderEvent) {
        logger.info("Received order : " + messageWithOrderEvent.getPayload().orderID);
        OrderEvent orderEvent = messageWithOrderEvent.getPayload();
        Optional<TracingMetadata> optionalTracingMetadata = TracingMetadata.fromMessage(messageWithOrderEvent);
        if (optionalTracingMetadata.isPresent()) {
            TracingMetadata tracingMetadata = optionalTracingMetadata.get();
            Context context = tracingMetadata.getCurrentContext();
            try (Scope scope = context.makeCurrent()) {
                createProcessedOrderEventSpan(orderEvent, context);
                switch (orderEvent.getType()) {
                    case OrderEvent.ORDER_CREATED_TYPE:
                        processOrderCreatedEvent(orderEvent);
                        break;
                    case OrderEvent.ORDER_UPDATED_TYPE:
                        logger.info("Receive order update " + orderEvent.status);
                        if (orderEvent.status.equals(OrderEvent.ORDER_ON_HOLD_TYPE)) {
                            compensateOrder(orderEvent.orderID, orderEvent.quantity);
                        } else {
                            logger.info("Do future processing in case of order update");
                        }

                        break;
                    default:
                        break;
                }
            }
        }
        return messageWithOrderEvent.ack();
    }

    private void createProcessedOrderEventSpan(final OrderEvent orderEvent, final Context context) {
        final String spanName = MessageFormat.format("processed event[{0}]", orderEvent.getType());
        final SpanBuilder spanBuilder = TRACER.spanBuilder(spanName).setParent(context);
        final Span span = spanBuilder.startSpan();
        final ObjectWriter ow = new ObjectMapper().writer().withDefaultPrettyPrinter();
        try {
            final String orderEventJson = ow.writeValueAsString(orderEvent);
            span.setAttribute("processed.event", orderEventJson);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        } finally {
            span.end();
        }
    }

    /**
     * When order created, search for voyage close to the pickup location, and a distination close
     * for a given date
     */
    public VoyageEvent processOrderCreatedEvent(OrderEvent oe) {
        OrderCreatedEvent oce = (OrderCreatedEvent) oe.payload;
        Voyage voyage = repo.getVoyageForOrder(oe.orderID,
                oce.pickupCity,
                oce.destinationCity,
                oe.quantity);
        VoyageEvent ve = new VoyageEvent();
        if (voyage == null) {
            // normally do nothing
            logger.info("No voyage found for " + oce.pickupCity);
        } else {
            VoyageAllocated voyageAssignedEvent = new VoyageAllocated(oe.orderID);
            ve.voyageID = voyage.voyageID;
            ve.setType(VoyageEvent.TYPE_VOYAGE_ASSIGNED);
            ve.payload = voyageAssignedEvent;

            voyageEventProducer.sendEvent(ve.voyageID, ve);
        }
        return ve;
    }

    public void compensateOrder(String txid, long capacity) {
        logger.info("Compensate on order " + txid);
        repo.cleanTransaction(txid, capacity);
    }
}
