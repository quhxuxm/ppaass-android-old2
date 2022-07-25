package com.ppaass.agent.common;

public interface IFlowObserver<E> {
    void onFlowElement(E element);
}
