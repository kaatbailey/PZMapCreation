package pzformat;
import java.nio.file.*;
import java.util.*;
/** Cross-language oracle for FootprintSnap. Corpus generated from a fixed
 *  java.util.Random seed so both trees build identical inputs. */
public final class FootprintOracle {
    static String bits(double d){ return String.format("%016x", Double.doubleToRawLongBits(d)); }
    public static void main(String[] a) throws Exception {
        List<String> out = new ArrayList<>();
        int[][][] fixed = {
            {{10,10},{20,10},{20,16},{10,16}},
            {{100,90},{110,100},{100,110},{90,100}},
            {{0,0},{30,15},{26,23},{-4,8}},
            {{0,0},{1,1}},
            {{-20,-20},{-10,-20},{-10,-14},{-20,-14}},
            {{0,0},{5,0},{5,5},{0,5},{0,0}},
            {{3,3},{3,3},{3,3}},
        };
        for(int i=0;i<fixed.length;i++){
            List<int[]> ring=new ArrayList<>(); for(int[] p:fixed[i]) ring.add(p);
            FootprintSnap.Rect r=FootprintSnap.snap(ring);
            out.add("X\t"+i+"\t"+(r==null?"null":r.toString())+"\t"
                    +(FootprintSnap.isAxisAligned(ring)?"1":"0"));
        }
        Random rng=new Random(20260831L);
        for(int k=0;k<20000;k++){
            int n=3+rng.nextInt(10);
            double cx=rng.nextDouble()*2000.0-1000.0;
            double cy=rng.nextDouble()*2000.0-1000.0;
            double sx=0.5+rng.nextDouble()*60.0;
            double sy=0.5+rng.nextDouble()*60.0;
            double[][] ring=new double[n][2];
            for(int i=0;i<n;i++){
                double ux=rng.nextDouble()*2.0-1.0;
                double uy=rng.nextDouble()*2.0-1.0;
                ring[i][0]=cx+ux*sx; ring[i][1]=cy+uy*sy;
            }
            FootprintSnap.Rect r=FootprintSnap.snap(ring);
            double[][] h=FootprintSnap.hull(FootprintSnap.dedupeExact(ring));
            double[] m=FootprintSnap.minAreaRect(h);
            out.add("R\t"+k+"\t"+(r==null?"null":r.toString())
                    +"\t"+bits(FootprintSnap.area(ring))
                    +"\t"+h.length
                    +"\t"+bits(m[2])+"\t"+bits(m[3]));
        }
        for(int fi=1; fi<a.length; fi++){
            GeoJson g=GeoJson.read(Path.of(a[fi]));
            if(g.features.isEmpty()) continue;
            double minLon=1e18,maxLat=-1e18;
            for(GeoJson.Feature f:g.features) for(List<double[]> r:f.rings) for(double[] p:r){
                minLon=Math.min(minLon,p[0]); maxLat=Math.max(maxLat,p[1]); }
            double mPerLat=111320.0, mPerLon=111320.0*Math.cos(maxLat*Math.PI/180.0);
            for(int i=0;i<g.features.size();i++){
                List<List<double[]>> rings=g.features.get(i).rings;
                for(int ri=0;ri<rings.size();ri++){
                    List<double[]> rr=rings.get(ri);
                    double[][] ring=new double[rr.size()][2];
                    for(int q=0;q<rr.size();q++){
                        ring[q][0]=(rr.get(q)[0]-minLon)*mPerLon;
                        ring[q][1]=(maxLat-rr.get(q)[1])*mPerLat; }
                    FootprintSnap.Rect r=FootprintSnap.snap(ring);
                    double[][] h=FootprintSnap.hull(FootprintSnap.dedupeExact(ring));
                    out.add("G\t"+(fi+1)+"\t"+i+"\t"+ri+"\t"+(r==null?"null":r.toString())
                            +"\t"+bits(FootprintSnap.area(ring))+"\t"+h.length);
                }
            }
        }
        Files.write(Path.of(a[0]), out);
        System.out.println("java footprint: "+out.size()+" lines -> "+a[0]);
    }
}
