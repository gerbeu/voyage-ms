package ibm.eda.kc.voyagems.infra.events.order;

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

    private final Logger logger = Logger.getLogger(OrderAgent.class.getName());

    @Inject
    VoyageRepository repo;

    @Inject
    VoyageEventProducer voyageEventProducer;

    @Incoming("orders")
    public CompletionStage<Void> processOrder(Message<OrderEvent> message) {
        OrderEvent orderEvent = message.getPayload();
        logger.info("Received order: " + orderEvent.orderID);
        Optional<TracingMetadata> tracingMetadata = TracingMetadata.fromMessage(message);

        if (!tracingMetadata.isPresent()) {
            return message.ack();
        }

        TracingMetadata metadata = tracingMetadata.get();
        Context context = metadata.getCurrentContext();

        try (Scope scope = context.makeCurrent()) {
            createProcessedOrderEventSpan(orderEvent, context);
            handleOrderEvent(orderEvent);
        }

        return message.ack();
    }

    private void handleOrderEvent(OrderEvent orderEvent) {
        switch (orderEvent.getType()) {
            case OrderEvent.ORDER_CREATED_TYPE:
                processOrderCreatedEvent(orderEvent);
                break;
            case OrderEvent.ORDER_UPDATED_TYPE:
                logger.info("Received order update: " + orderEvent.status);
                if (OrderEvent.ORDER_ON_HOLD_TYPE.equals(orderEvent.status)) {
                    compensateOrder(orderEvent.orderID, orderEvent.quantity);
                } else {
                    logger.info("Do future processing for order update");
                }
                break;
            default:
                break;
        }
    }

    private void createProcessedOrderEventSpan(OrderEvent orderEvent, Context context) {
        String spanName = String.format("processed event [%s]", orderEvent.getType());
        SpanBuilder spanBuilder = TRACER.spanBuilder(spanName).setParent(context);
        Span span = spanBuilder.startSpan();
        ObjectWriter objectWriter = new ObjectMapper().writer().withDefaultPrettyPrinter();

        try {
            String orderEventJson = objectWriter.writeValueAsString(orderEvent);
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
        OrderCreatedEvent orderCreatedEvent = (OrderCreatedEvent) oe.payload;
        Voyage voyage = repo.getVoyageForOrder(oe.orderID,
                orderCreatedEvent.pickupCity,
                orderCreatedEvent.destinationCity,
                oe.quantity);
        VoyageEvent voyageEvent = new VoyageEvent();
        if (voyage == null) {
            logger.info("No voyage found for pickup in city " + orderCreatedEvent.pickupCity);
        } else {
            VoyageAllocated voyageAssigned = new VoyageAllocated(oe.orderID);
            voyageEvent.voyageID = voyage.voyageID;
            voyageEvent.setType(VoyageEvent.TYPE_VOYAGE_ASSIGNED);
            voyageEvent.payload = voyageAssigned;
            voyageEventProducer.sendEvent(voyageEvent.voyageID, voyageEvent);
        }
        return voyageEvent;
    }

    public void compensateOrder(String txid, long capacity) {
        logger.info("Compensate on order " + txid);
        repo.cleanTransaction(txid, capacity);
    }
}
