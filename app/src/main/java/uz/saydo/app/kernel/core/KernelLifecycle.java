package uz.saydo.app.kernel.core;

/**
 * Standard contract for kernel subsystem lifecycles.
 */
public interface KernelLifecycle {
    void initialize();
    void start();
    void stop();
    void destroy();
}
