import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import java.util.*;
public class Defaults {
  public static void main(String[] a) {
    Map<String,Object> p = ProducerConfig.configDef().defaultValues();
    Map<String,Object> c = ConsumerConfig.configDef().defaultValues();
    for (String k : List.of("acks","enable.idempotence","retries","delivery.timeout.ms","request.timeout.ms","linger.ms","batch.size","max.in.flight.requests.per.connection","buffer.memory","max.block.ms","partitioner.class","compression.type"))
      System.out.println("P " + k + " = " + p.get(k));
    for (String k : List.of("enable.auto.commit","auto.commit.interval.ms","auto.offset.reset","session.timeout.ms","heartbeat.interval.ms","max.poll.interval.ms","max.poll.records","partition.assignment.strategy","group.protocol","isolation.level","group.remote.assignor"))
      System.out.println("C " + k + " = " + (c.containsKey(k) ? c.get(k) : "(none)"));
  }
}
