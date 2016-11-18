package pl.grzeslowski.smarthome.rf24.examples;

import org.apache.commons.lang3.time.DurationFormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.grzeslowski.smarthome.rf24.BasicRf24;
import pl.grzeslowski.smarthome.rf24.Rf24Adapter;
import pl.grzeslowski.smarthome.rf24.examples.cmd_line.ArgsReader;
import pl.grzeslowski.smarthome.rf24.exceptions.ReadRf24Exception;
import pl.grzeslowski.smarthome.rf24.exceptions.WriteRf24Exception;
import pl.grzeslowski.smarthome.rf24.helpers.Payload;
import pl.grzeslowski.smarthome.rf24.helpers.Pins;
import pl.grzeslowski.smarthome.rf24.helpers.Pipe;
import pl.grzeslowski.smarthome.rf24.helpers.Retry;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Date;
import java.util.concurrent.TimeUnit;

public class Rf24PingPongServerExample {
    static {
        Rf24Adapter.loadLibrary();
    }

    public static final Pipe WRITE_PIPE = new Pipe("1Node");
    public static final Pipe READ_PIPE = new Pipe("2Node");

    private static final Logger logger = LoggerFactory.getLogger(Rf24PingPongServerExample.class);
    private static final long WAITING_FOR_RESPONSE_TIME = TimeUnit.SECONDS.toMillis(1);
    private static final long TIME_TO_SLEEP = TimeUnit.SECONDS.toMillis(1);

    private final ArgsReader argsReader = new ArgsReader();
    private final BasicRf24 rf24;
    private final ByteBuffer sendBuffer;
    private final ByteBuffer readBuffer;

    // read from command line
    private final Pins pins;
    private final Retry retry;
    private final Payload payload;
    private final long numberOfSends;

    public static void main(String[] args) throws Exception {
        final Rf24PingPongServerExample server = new Rf24PingPongServerExample(args);
        server.init();
        server.run();
    }

    public Rf24PingPongServerExample(String[] args) {
        pins = argsReader.readPins(args);
        retry = argsReader.readRetry(args);
        payload = new Payload((short) (Long.SIZE / Byte.SIZE));
        numberOfSends = argsReader.readNumberOfSends(args);

        rf24 = new Rf24Adapter(pins, retry, payload);

        sendBuffer = ByteBuffer.allocate(Long.SIZE / Byte.SIZE);
        sendBuffer.order(ByteOrder.LITTLE_ENDIAN);

        readBuffer = ByteBuffer.allocate(Long.SIZE / Byte.SIZE);
        readBuffer.order(ByteOrder.LITTLE_ENDIAN);
    }

    public void init() {
        logger.info("Init RF24");
        logger.info("Write pipe: {}", WRITE_PIPE);
        logger.info("Read  pipe: {}", READ_PIPE);
        logger.info("Pins: {}", pins);
        logger.info("Retry: {}", retry);
        logger.info("Payload: {}", payload);

        rf24.init();

        if (rf24 instanceof Rf24Adapter) {
            ((Rf24Adapter) rf24).printDetails();
        }
    }

    public void run() throws InterruptedException {
        for (long counter = 1; counter <= numberOfSends; counter++) {
            logger.info("Iteration #{}", counter);

            // send
            send();

            // read
            read();

            // Sleep
            Thread.sleep(TIME_TO_SLEEP);
        }
    }

    private void send() {
        long time = new Date().getTime();
        logger.info("Now sending {}...", time);
        sendBuffer.clear();
        sendBuffer.putLong(time);
        try {
            final boolean wrote = rf24.write(WRITE_PIPE, sendBuffer.array());
            if (!wrote) {
                logger.error("Failed sending {}!", time);
            }
        } catch (WriteRf24Exception ex) {
            logger.error("Failed sending " + time + "!", ex);
        }
    }

    private void read() {
        readBuffer.clear();
        final long startedAt = new Date().getTime();
        boolean wasRead = false;
        try {
            while (!wasRead && System.currentTimeMillis() <= startedAt + WAITING_FOR_RESPONSE_TIME) {
                wasRead = rf24.read(READ_PIPE, readBuffer);
            }
        } catch (ReadRf24Exception ex) {
            logger.error("Error while reading!", ex);
        }

        if (wasRead) {
            final long response = readBuffer.getLong();
            final long now = new Date().getTime();
            final long roundTripTime = now - response;
            logger.info("Got {}, Round trip time {} [s].", response, DurationFormatUtils.formatDuration(roundTripTime, "ss.SS", true));
        } else {
            logger.error("Timeout!");
        }
    }
}
