package test_amqp;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import test_amqp.calculator.GoogleMapsDistanceCalculator;
import test_amqp.calculator.PriceCalculator;
import test_amqp.calculator.PriceRequestInternal;
import test_amqp.entities.TicketPriceDetails;
import test_amqp.exception.TicketReferenceNotFoundException;
import test_amqp.model.*;
import test_amqp.repos.TicketPriceDetailsRepository;

import java.math.BigDecimal;

@Service
public class TicketDistributionService {

    @Autowired
    private GoogleMapsDistanceCalculator googleMapsDistanceCalculator;

    @Autowired
    private TicketPriceDetailsRepository ticketPriceDetailsRepository;

    public TicketDistributionService(GoogleMapsDistanceCalculator distanceCalculator) {
        this.googleMapsDistanceCalculator = distanceCalculator;
    }

    public TicketDistributionService() {}


    public PriceInformation generatePriceInformation(TicketRequest ticketRequest){
        BigDecimal price = calculatePriceBasedOnDistanceAndTicketType(ticketRequest);
        TicketPriceDetails ticketPriceDetails = new TicketPriceDetails();
        ticketPriceDetails.setPrice(price);
        ticketPriceDetails.setTicketType(ticketRequest.getTicketType());
        ticketPriceDetails.setTo(ticketRequest.getJourneyDirections().getTo());
        ticketPriceDetails.setFromDirection(ticketRequest.getJourneyDirections().getFrom());
        ticketPriceDetailsRepository.save(ticketPriceDetails);
        return getPriceInformation(ticketPriceDetails, ticketRequest.getJourneyDirections(), price);
    }


    private BigDecimal calculatePriceBasedOnDistanceAndTicketType(TicketRequest ticketRequest) {
        JourneyDirections journeyDirections = ticketRequest.getJourneyDirections();
        BigDecimal distance = googleMapsDistanceCalculator.calculateDistance(journeyDirections.getFrom(), journeyDirections.getTo());
        PriceRequestInternal priceRequestInternal = PriceRequestInternal.PriceRequestInternalBuilder.aPriceRequestInternal()
                .withStudentPrice(ticketRequest.isStudentTicket())
                .withDistance(distance)
                .withNumberOfTickets(ticketRequest.getNumberOfTickets())
                .withTicketType(ticketRequest.getTicketType())
                .build();
        return PriceCalculator.calculatePricePerDistanceTicketTypeAndNumberofTickets(priceRequestInternal);
    }

    public Ticket generateTicket(TicketPayment payment) throws TicketReferenceNotFoundException {
        TicketPriceDetails ticketPriceDetails = ticketPriceDetailsRepository.findById(payment.getTicketId());
        if (ticketPriceDetails == null) {
            throw new TicketReferenceNotFoundException("Ticket with the given ID not found: " + payment.getTicketId());
        }
        BigDecimal expectedPayment = ticketPriceDetails.getPrice();
        BigDecimal paidAmount = payment.getPaymentAmount();
        JourneyDirections journeyDirections = new JourneyDirections(ticketPriceDetails.getFromDirection(), ticketPriceDetails.getTo());

        BigDecimal changeRequired = paidAmount.subtract(expectedPayment);
         if (expectedPayment.compareTo(paidAmount) > 0) {
            BigDecimal leftToPay = expectedPayment.subtract(paidAmount);
            ticketPriceDetails.setPrice(leftToPay);
            ticketPriceDetailsRepository.save(ticketPriceDetails);
             return getPriceInformation(ticketPriceDetails, journeyDirections, leftToPay);
        } else {
            return getTicketAndClearDatabase(payment, ticketPriceDetails, journeyDirections, changeRequired);
        }
    }

    private PriceInformation getPriceInformation(TicketPriceDetails ticketPriceDetails, JourneyDirections journeyDirections, BigDecimal price) {
        return new PriceInformation.PriceInformationBuilder().withJourneyDirections(journeyDirections)
                        .withTicketType(ticketPriceDetails.getTicketType())
                        .withTotalPrice(price)
                        .withTicketId(ticketPriceDetails.getId())
                        .build();
    }

    private Ticket getTicketAndClearDatabase(TicketPayment payment, TicketPriceDetails ticketPriceDetails, JourneyDirections journeyDirections, BigDecimal changeRequied) {
        Ticket ticket = new Ticket(payment.getPaymentAmount(), journeyDirections, ticketPriceDetails.getTicketType());
        if (changeRequied.compareTo(BigDecimal.ZERO) != 0) {
            ticket.setChange(changeRequied);
        }
        ticketPriceDetailsRepository.delete(ticketPriceDetails);
        return ticket;
    }
}
