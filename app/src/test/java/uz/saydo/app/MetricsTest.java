package uz.saydo.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import uz.saydo.app.metrics.MetricsSummary;

public class MetricsTest {

    private static JSONObject ev(String json) throws Exception {
        return new JSONObject(json);
    }

    @Test
    public void falseOkIsCountedWhenAppSaysOkButHumanSaysNo() throws Exception {
        List<JSONObject> events = new ArrayList<>();
        events.add(ev("{e:'run_end', run:1, ok:true, fails:0, first:-1}"));
        events.add(ev("{e:'verdict', run:1, ok:false, broke:9}"));
        events.add(ev("{e:'run_end', run:2, ok:true, fails:0, first:-1}"));
        events.add(ev("{e:'verdict', run:2, ok:true, broke:-1}"));
        events.add(ev("{e:'run_end', run:3, ok:false, fails:2, first:4}"));
        events.add(ev("{e:'verdict', run:3, ok:false, broke:5}"));

        MetricsSummary.Result r = MetricsSummary.compute(events);
        assertEquals(3, r.runs);
        assertEquals(2, r.runsReportedOk);
        assertEquals(1, r.runsWithStepFailures);
        assertEquals(3, r.verdicts);
        assertEquals(1, r.verdictOk);
        assertEquals(1, r.falseOk);                       // only run 1
        assertEquals(Integer.valueOf(1), r.brokeAtStep.get(9));
        assertEquals(Integer.valueOf(1), r.firstFailStep.get(5)); // 0-based 4 -> step 5
    }

    @Test
    public void emptyLogFormatsWithoutCrash() {
        String text = MetricsSummary.format(MetricsSummary.compute(new ArrayList<>()), null);
        assertTrue(text.contains("hali o'tkazilmagan"));
    }
}
