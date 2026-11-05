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
import uz.saydo.app.metrics.VoiceBench;

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
    public void voiceEventsSplitFinalAndPartial() throws Exception {
        List<JSONObject> events = Arrays.asList(
                ev("{e:'voice_heard', text:'open youtube', trig:'open youtube', partial:false, lat:420}"),
                ev("{e:'voice_heard', text:'hello', trig:'', partial:false, lat:300}"),
                ev("{e:'voice_heard', text:'call mom', trig:'call mom', partial:true, lat:-1}"),
                ev("{e:'voice_error', code:7, name:'NO_MATCH'}"),
                ev("{e:'voice_error', code:7, name:'NO_MATCH'}"));
        MetricsSummary.Result r = MetricsSummary.compute(events);
        assertEquals(2, r.heardFinal);
        assertEquals(1, r.matchedFinal);
        assertEquals(1, r.matchedPartial);
        assertEquals(Arrays.asList(420L), r.matchLatencies);
        assertEquals(Integer.valueOf(2), r.errors.get("NO_MATCH(7)"));
    }

    @Test
    public void benchPerTriggerRates() throws Exception {
        List<JSONObject> events = Arrays.asList(
                ev("{e:'bench_voice', exp:'a', got:'a', res:'HIT', lat:100}"),
                ev("{e:'bench_voice', exp:'a', got:'', res:'MISS', lat:-1}"),
                ev("{e:'bench_voice', exp:'b', got:'a', res:'WRONG', lat:200}"));
        MetricsSummary.Result r = MetricsSummary.compute(events);
        assertEquals(3, r.benchTrials);
        assertEquals(1, r.benchHit);
        assertEquals(1, r.benchMiss);
        assertEquals(1, r.benchWrong);
        assertEquals(1, r.benchPerTrigger.get("a")[0]);
        assertEquals(2, r.benchPerTrigger.get("a")[1]);
        assertEquals(Arrays.asList(100L), r.benchLatencies); // WRONG latency not counted

        String text = MetricsSummary.format(r, "device=test");
        assertTrue(text.contains("33%"));
        assertTrue(text.contains("device=test"));
    }

    @Test
    public void gateOpensCountMatchesUntilNextOpening() throws Exception {
        List<JSONObject> events = Arrays.asList(
                ev("{e:'gate_open', mode:'stream', db:-20}"),
                ev("{e:'voice_heard', text:'x', trig:'open youtube', partial:false, lat:300}"),
                ev("{e:'voice_heard', text:'y', trig:'open youtube', partial:false, lat:300}"), // same opening, not double counted
                ev("{e:'gate_open', mode:'stream', db:-18}"),                                    // door slam, nothing heard
                ev("{e:'gate_open', mode:'restart', db:-15}"),
                ev("{e:'voice_heard', text:'z', trig:'', partial:false, lat:300}"),
                ev("{e:'gate_fallback', reason:'silent_NO_MATCH'}"));
        MetricsSummary.Result r = MetricsSummary.compute(events);
        assertEquals(3, r.gateOpens);
        assertEquals(1, r.gateOpensMatched);
        assertEquals(1, r.gateFallbacks);
        assertEquals(Integer.valueOf(2), r.gateModes.get("stream"));
        assertTrue(MetricsSummary.format(r, null).contains("Ovoz darvozasi"));
    }

    @Test
    public void emptyLogFormatsWithoutCrash() {
        String text = MetricsSummary.format(MetricsSummary.compute(new ArrayList<>()), null);
        assertTrue(text.contains("hali o'tkazilmagan"));
    }

    @Test
    public void voiceBenchScoresHitWrongMiss() {
        VoiceBench b = new VoiceBench(Arrays.asList("a", "b"), 2, new Random(1));
        assertEquals(4, b.total());

        // Match without a prompt is ignored
        assertNull(b.onMatch("a", 10));

        b.prompt(0);
        String first = b.current();
        assertEquals(VoiceBench.Res.HIT, b.onMatch(first, 350).res);

        b.prompt(0);
        String second = b.current();
        String other = second.equals("a") ? "b" : "a";
        VoiceBench.Trial wrong = b.onMatch(other, 400);
        assertEquals(VoiceBench.Res.WRONG, wrong.res);
        assertEquals(other, wrong.got);

        b.prompt(0);
        b.onHeard("something else");
        VoiceBench.Trial miss = b.onTimeout();
        assertEquals(VoiceBench.Res.MISS, miss.res);
        assertEquals("something else", miss.got);

        // A late match after timeout must not count for the next prompt
        assertNull(b.onMatch("a", 10));

        b.prompt(0);
        b.onMatch(b.current(), 1);
        assertTrue(b.isDone());
        assertNull(b.current());
        assertEquals(2, b.count(VoiceBench.Res.HIT));
    }

    @Test
    public void voiceBenchEachRoundContainsEveryTriggerOnce() {
        List<String> t = Arrays.asList("a", "b", "c");
        VoiceBench b = new VoiceBench(t, 3, new Random(42));
        List<String> seen = new ArrayList<>();
        while (!b.isDone()) {
            b.prompt(0);
            seen.add(b.current());
            b.onMatch(b.current(), 0);
        }
        for (int r = 0; r < 3; r++) {
            List<String> round = new ArrayList<>(seen.subList(r * 3, r * 3 + 3));
            round.sort(String::compareTo);
            assertEquals(t, round);
        }
    }
}
