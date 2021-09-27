package org.dcsa.tnt.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.dcsa.core.events.model.EquipmentEvent;
import org.dcsa.core.events.model.Event;
import org.dcsa.core.events.model.OperationsEvent;
import org.dcsa.core.events.model.ShipmentEvent;
import org.dcsa.core.events.model.TransportEvent;
import org.dcsa.core.events.model.UnmappedEvent;
import org.dcsa.core.events.model.enums.EventType;
import org.dcsa.core.events.repository.EventRepository;
import org.dcsa.core.events.repository.PendingEventRepository;
import org.dcsa.core.events.repository.UnmappedEventRepository;
import org.dcsa.core.events.service.*;
import org.dcsa.core.events.service.impl.GenericEventServiceImpl;
import org.dcsa.core.exception.NotFoundException;
import org.dcsa.core.extendedrequest.ExtendedRequest;
import org.dcsa.tnt.service.TNTEventService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Service
public class TNTEventServiceImpl extends GenericEventServiceImpl implements TNTEventService {
  private final UnmappedEventRepository unmappedEventRepository;

  public TNTEventServiceImpl(
      TransportEventService transportEventService,
      EquipmentEventService equipmentEventService,
      ShipmentEventService shipmentEventService,
      OperationsEventService operationsEventService,
      EventRepository eventRepository,
      PendingEventRepository pendingEventRepository,
      UnmappedEventRepository unmappedEventRepository) {
    super(
        shipmentEventService,
        transportEventService,
        equipmentEventService,
        operationsEventService,
        eventRepository,
        pendingEventRepository);
    this.unmappedEventRepository = unmappedEventRepository;
  }

    @Override
    public Flux<Event> findAllExtended(ExtendedRequest<Event> extendedRequest) {
        return super.findAllExtended(extendedRequest).concatMap(event -> {
            switch (event.getEventType()) {
                case TRANSPORT:
                    return transportEventService.loadRelatedEntities((TransportEvent) event);
                case EQUIPMENT:
                    return equipmentEventService.loadRelatedEntities((EquipmentEvent) event);
                case SHIPMENT:
                    return shipmentEventService.loadRelatedEntities((ShipmentEvent) event);
                default:
                    return Mono.empty();
            }
        });
    }

    @Override
    public Mono<Event> findById(UUID id) {
        return Mono.<Event>empty()
                .switchIfEmpty(getTransportEventRelatedEntities(id))
                .switchIfEmpty(getShipmentEventRelatedEntities(id))
                .switchIfEmpty(getEquipmentEventRelatedEntities(id))
                .switchIfEmpty(Mono.error(new NotFoundException("No event was found with id: " + id)));
    }

    @Override
    public Mono<Event> create(Event event) {
        System.out.println("TNTEventServiceImpl>>create>>"+event.toString());
        if (event.getEventCreatedDateTime() == null) {
            event.setEventCreatedDateTime(OffsetDateTime.now());
        }
        return super.create(event);
        //        .flatMap(savedEvent -> {
        //               System.out.println(savedEvent.getEventID());
        //               return pendingEventRepository.enqueueUnmappedEventID(event.getEventID())
        //                   .thenReturn(savedEvent);
        //           }
        //      );
    }

    @Override
    public Mono<Event> update(Event event) {
        System.out.println("TNTEventServiceImpl>>update>>"+event.toString());
        if (event.getEventCreatedDateTime() == null) {
            event.setEventCreatedDateTime(OffsetDateTime.now());
        }

        return updateInteral(event)
                .flatMap((ee) -> {
                            UnmappedEvent unmappedEvent = new UnmappedEvent();
                            unmappedEvent.setNewRecord(false);
                            unmappedEvent.setEventID(ee.getEventID());
                            unmappedEvent.setEnqueuedAtDateTime(ee.getEventCreatedDateTime());
                            return this.unmappedEventRepository.save(unmappedEvent);
                }).thenReturn(event);
    }

    // TODO needs to move to Generic service
    private Mono<Event> updateInteral(Event event) {
        EventType eventType = event.getEventType();
        String carrierBookingReference = event.getCarrierBookingReference();
        if (!this.getSupportedEvents().contains(event.getEventType())) {
            throw new IllegalArgumentException("Unsupported event type: " + event.getEventType());
        } else {
            event.setEventType((EventType)null);
            event.setCarrierBookingReference((String)null);
            Mono eventMono;
            switch(eventType) {
                case SHIPMENT:
                    eventMono = this.shipmentEventService.update((ShipmentEvent)event);
                    break;
                case TRANSPORT:
                    eventMono = this.transportEventService.update((TransportEvent)event);
                    break;
                case EQUIPMENT:
                    eventMono = this.equipmentEventService.update((EquipmentEvent)event);
                    break;
                case OPERATIONS:
                    eventMono = this.operationsEventService.update((OperationsEvent)event);
                    break;
                default:
                    return Mono.error(new IllegalStateException("Unexpected value: " + event.getEventType()));
            }

            return eventMono.doOnNext((e) -> {
                ((Event)e).setEventType(eventType);
                ((Event)e).setCarrierBookingReference(carrierBookingReference);
            }).cast(Event.class);
        }
    }
}
