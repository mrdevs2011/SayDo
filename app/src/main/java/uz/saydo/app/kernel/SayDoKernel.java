package uz.saydo.app.kernel;

import android.content.Context;

import uz.saydo.app.data.MacroRepository;
import uz.saydo.app.data.SettingsRepository;
import uz.saydo.app.kernel.core.EventBus;
import uz.saydo.app.kernel.core.HalProvider;
import uz.saydo.app.kernel.core.KernelLifecycle;
import uz.saydo.app.kernel.daemon.LocalDaemonServer;
import uz.saydo.app.kernel.data.SecurityVault;
import uz.saydo.app.kernel.data.StrongBoxVault;
import uz.saydo.app.kernel.mesh.ContinuityMeshEngine;
import uz.saydo.app.kernel.voice.VoiceFeedbackEngine;
import uz.saydo.app.kernel.workflow.ContextMemory;
import uz.saydo.app.kernel.workflow.PipelineRunner;

/**
 * Grand Unified SayDo Micro-Kernel (The Harmonizer).
 * Central autonomous orchestrator binding HAL, cryptographic vaults,
 * sensory perception, workflow pipelines, and local continuity mesh.
 */
public final class SayDoKernel implements KernelLifecycle {

    private static volatile SayDoKernel sInstance;

    public static synchronized SayDoKernel init(Context context) {
        if (sInstance == null) {
            sInstance = new SayDoKernel(context);
            sInstance.initialize();
        }
        return sInstance;
    }

    public static SayDoKernel getInstance() {
        if (sInstance == null) {
            throw new IllegalStateException("SayDoKernel not initialized");
        }
        return sInstance;
    }

    private final Context context;
    private final HalProvider hal;
    private final EventBus eventBus;
    private final SecurityVault securityVault;
    private final StrongBoxVault strongBoxVault;
    private final ContextMemory contextMemory;
    private final PipelineRunner pipelineRunner;
    private final ContinuityMeshEngine meshEngine;
    private final LocalDaemonServer daemonServer;
    private final VoiceFeedbackEngine voiceFeedback;

    private boolean isRunning = false;

    private SayDoKernel(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        this.context = context.getApplicationContext();
        this.hal = new HalProvider(this.context);
        this.eventBus = EventBus.getDefault();
        this.securityVault = SecurityVault.getInstance();
        this.strongBoxVault = new StrongBoxVault(this.context);
        this.contextMemory = ContextMemory.getInstance();
        this.pipelineRunner = new PipelineRunner(this.context);
        this.meshEngine = new ContinuityMeshEngine(this.context);
        this.daemonServer = new LocalDaemonServer(8765, MacroRepository.getInstance(this.context));
        this.voiceFeedback = new VoiceFeedbackEngine(this.context);
    }

    @Override
    public synchronized void initialize() {
        android.util.Log.i("SayDoKernel", "SayDo Grand Unified Micro-Kernel initialized");
    }

    @Override
    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;
        meshEngine.startMesh();
        daemonServer.start();
        android.util.Log.i("SayDoKernel", "SayDo Micro-Kernel services started");
    }

    @Override
    public synchronized void stop() {
        if (!isRunning) return;
        isRunning = false;
        meshEngine.stopMesh();
        daemonServer.stop();
        voiceFeedback.stop();
        contextMemory.clear();
        android.util.Log.i("SayDoKernel", "SayDo Micro-Kernel services stopped");
    }

    @Override
    public synchronized void destroy() {
        stop();
        voiceFeedback.shutdown();
        eventBus.clear();
        android.util.Log.i("SayDoKernel", "SayDo Micro-Kernel destroyed");
    }

    public Context getContext() {
        return context;
    }

    public HalProvider getHal() {
        return hal;
    }

    public EventBus getEventBus() {
        return eventBus;
    }

    public SecurityVault getSecurityVault() {
        return securityVault;
    }

    public StrongBoxVault getStrongBoxVault() {
        return strongBoxVault;
    }

    public ContextMemory getContextMemory() {
        return contextMemory;
    }

    public PipelineRunner getPipelineRunner() {
        return pipelineRunner;
    }

    public ContinuityMeshEngine getMeshEngine() {
        return meshEngine;
    }

    public LocalDaemonServer getDaemonServer() {
        return daemonServer;
    }

    public VoiceFeedbackEngine getVoiceFeedback() {
        return voiceFeedback;
    }

    public boolean isRunning() {
        return isRunning;
    }
}
