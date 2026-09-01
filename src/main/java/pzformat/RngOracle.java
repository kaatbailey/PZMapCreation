package pzformat;
import java.nio.file.*;
import java.util.*;
/** Cross-language oracle for java.util.Random. Emits the same draws the C++
 *  JavaRandom must reproduce. Doubles go out as raw bits so the comparison
 *  tests the generator, not two printf implementations. */
public final class RngOracle {
    public static void main(String[] args) throws Exception {
        long[] seeds = {0, 1, 12345, -1, 42, 1337L*31+200L*7919+200L, 1337L*131+7L, -9007199254740993L};
        List<String> out = new ArrayList<>();
        for (long s : seeds) {
            Random r = new Random(s);
            for (int i=0;i<200;i++) out.add(s+"\td\t"+i+"\t"+String.format("%016x", Double.doubleToRawLongBits(r.nextDouble())));
            Random r2 = new Random(s);
            for (int i=0;i<200;i++) out.add(s+"\ti\t"+i+"\t"+r2.nextInt(97));
            Random r3 = new Random(s);
            for (int i=0;i<200;i++) out.add(s+"\tp\t"+i+"\t"+r3.nextInt(64));
            Random r4 = new Random(s);
            for (int i=0;i<200;i++) out.add(s+"\tl\t"+i+"\t"+r4.nextLong());
            Random r5 = new Random(s);
            for (int i=0;i<200;i++) out.add(s+"\tb\t"+i+"\t"+(r5.nextBoolean()?1:0));
        }
        Files.write(Path.of(args[0]), out);
        System.out.println("java rng: "+out.size()+" draws -> "+args[0]);
    }
}
