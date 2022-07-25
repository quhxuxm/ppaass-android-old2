package com.ppaass.agent.common;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;

public abstract class AbstractFlowObservable<E> {
    private final Collection<IFlowObserver<E>> observers;

    protected AbstractFlowObservable() {
        observers = new ArrayList<>();
    }

    public void registerObserver(IFlowObserver<E> observer) {
        this.observers.add(observer);
    }

    protected abstract E readFlowElement();

    public void start() {
        E element = this.readFlowElement();
        while (element != null) {
            for (IFlowObserver<E> observer : this.observers) {
                try {
                    observer.onFlowElement(element);
                } catch (Throwable e) {
                    Log.e(AbstractFlowObservable.class.getName(), "Fail to invoke observer because of error.", e);
                }
            }
            element = this.readFlowElement();
        }
    }
}
