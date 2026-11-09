import org.apache.kafka.clients.producer.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.*;
import org.apache.kafka.common.TopicPartition;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.time.Duration;
public class Exp {
  public static void main(String[] a) throws Exception {
    String bs = "localhost:19092,localhost:29092,localhost:39092";
    if (a[0].equals("produce")) {
      String topic=a[1], acks=a[2]; int n=Integer.parseInt(a[3]); String prefix=a[4]; int dt=Integer.parseInt(a[5]);
      Properties p=new Properties();
      p.put("bootstrap.servers",bs); p.put("acks",acks); p.put("linger.ms","0");
      p.put("enable.idempotence", acks.equals("all")?"true":"false");
      p.put("delivery.timeout.ms",String.valueOf(dt)); p.put("request.timeout.ms",String.valueOf(Math.min(dt,5000)));
      p.put("key.serializer",StringSerializer.class.getName()); p.put("value.serializer",StringSerializer.class.getName());
      AtomicInteger ok=new AtomicInteger(); Map<String,Integer> err=new ConcurrentHashMap<>();
      long t0=System.currentTimeMillis();
      try (KafkaProducer<String,String> pr=new KafkaProducer<>(p)) {
        CountDownLatch l=new CountDownLatch(n);
        for(int i=1;i<=n;i++){ String k=prefix+"-"+i;
          pr.send(new ProducerRecord<>(topic,k,k),(md,ex)->{ if(ex==null) ok.incrementAndGet(); else err.merge(ex.getClass().getSimpleName()+": "+String.valueOf(ex.getMessage()).replaceAll("\\s+"," ").substring(0,Math.min(110,String.valueOf(ex.getMessage()).length())),1,Integer::sum); l.countDown(); }); }
        l.await();
      }
      System.out.println("acked="+ok+" errors="+err+" took="+(System.currentTimeMillis()-t0)+"ms");
    } else if (a[0].equals("count")) {
      String topic=a[1];
      Properties p=new Properties();
      p.put("bootstrap.servers",bs); p.put("group.id","count-"+UUID.randomUUID()); p.put("auto.offset.reset","earliest"); p.put("enable.auto.commit","false");
      p.put("key.deserializer",StringDeserializer.class.getName()); p.put("value.deserializer",StringDeserializer.class.getName());
      Map<String,Integer> byPrefix=new TreeMap<>(); int total=0; int idle=0;
      try (KafkaConsumer<String,String> c=new KafkaConsumer<>(p)) {
        c.subscribe(List.of(topic));
        while(idle<6){ ConsumerRecords<String,String> rs=c.poll(Duration.ofMillis(1000)); if(rs.isEmpty()){idle++;continue;} idle=0;
          for(ConsumerRecord<String,String> r:rs){ total++; byPrefix.merge(r.key().replaceAll("-\\d+$",""),1,Integer::sum);} }
        Set<TopicPartition> as=c.assignment(); System.out.println("end offsets="+c.endOffsets(as));
      }
      System.out.println("total="+total+" byPrefix="+byPrefix);
    }
  }
}
