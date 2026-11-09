import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;
import java.util.*;
public class Idem { public static void main(String[] a){
  for (String[] c : new String[][]{{"1",null},{"1","true"},{"all",null}}) {
    Properties p=new Properties(); p.put("bootstrap.servers","localhost:19092"); p.put("acks",c[0]); if(c[1]!=null) p.put("enable.idempotence",c[1]);
    p.put("key.serializer",StringSerializer.class.getName()); p.put("value.serializer",StringSerializer.class.getName());
    try (KafkaProducer<String,String> pr=new KafkaProducer<>(p)) { System.out.println("acks="+c[0]+" idem="+c[1]+" -> created OK"); }
    catch(Exception e){ System.out.println("acks="+c[0]+" idem="+c[1]+" -> "+e.getClass().getSimpleName()+": "+e.getMessage()); }
  }}}
