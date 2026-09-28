package com.db.api;

import java.util.List;

public interface ICommandDispatcher {

    Object dispatch(List<String> command);
}
