package io.plumery.eventstore.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.plumery.core.AggregateRoot;
import io.plumery.core.Event;
import io.plumery.core.ID;
import io.plumery.core.infrastructure.EventPublisher;
import io.plumery.core.infrastructure.EventStore;
import io.plumery.eventstore.jdbc.dbi.EventMapper;
import io.plumery.eventstore.jdbc.dbi.EventStreams;
import io.plumery.eventstore.jdbc.exception.ConcurrencyException;
import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.TransactionStatus;
import org.skife.jdbi.v2.VoidTransactionCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class JdbcEventStore implements EventStore {
    private static Logger LOG = LoggerFactory.getLogger(JdbcEventStore.class);
    private DBI dbi;
    private ObjectMapper objectMapper;
    private final EventPublisher eventPublisher;

    public JdbcEventStore(DBI dbi, ObjectMapper objectMapper, EventPublisher eventPublisher) {
        this.dbi = dbi;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void saveEvents(String streamName, String aggregateId, Iterable<? extends Event> events, int expectedVersion) {
        dbi.inTransaction(new VoidTransactionCallback() {
            @Override
            protected void execute(Handle handle, TransactionStatus transactionStatus) throws Exception {
                EventStreams streams = handle.attach(EventStreams.class);

                Integer currentVersion = streams.getCurrentStreamVersion(aggregateId);
                if (currentVersion == null) {
                    LOG.debug("Creating new Event Stream for aggregate [{}] with Id [{}]", streamName, aggregateId);
                    streams.createNewStream(streamName, aggregateId);
                }

                int version = expectedVersion;
                if (expectedVersion == -1) {
                    LOG.debug("Aggregate has unknown expected version [{}]", expectedVersion);
                    version = currentVersion == null ? 0 : currentVersion;
                } else if (currentVersion != expectedVersion) {
                    throw new ConcurrencyException("The expected version ["+expectedVersion+"] of the aggregate does not match the current version ["+currentVersion+"] of the aggregate",
                            ID.fromObject(aggregateId), currentVersion);
                }



                for (Event event : events) {
                    version++;
                    event.version = version;
                    streams.appendNewEvent(aggregateId, event.id.toString(),
                            serializeEvent(event),
                            typeOf(event),
                            event.version);

                    //TODO: to be replaced with CDC
                    eventPublisher.publish(streamName, event);
                }

                LOG.debug("Setting new current version [{}] for aggregate [{}] with Id [{}]", version, streamName, aggregateId);
                streams.setCurrentStreamVersion(aggregateId, version);
            }
        });
    }

    @Override
    public Iterable<? extends Event> getEventsForAggregate(Class<? extends AggregateRoot> aggregate, String aggregateId) {
        dbi.registerMapper(new EventMapper(objectMapper));

        return dbi.withHandle(handle -> {
            EventStreams streams = handle.attach(EventStreams.class);
            return streams.loadEvents(aggregateId);
        });
    }

    private static String typeOf(Event event) {
        return TypeFactory.defaultInstance().constructType(event.getClass()).toCanonical();
    }

    private byte[] serializeEvent(Event event) {
        try {
            return objectMapper.writeValueAsBytes(event);
        } catch (IOException e) {
            throw new RuntimeException("Unable to serialize the event", e);
        }
    }
}
