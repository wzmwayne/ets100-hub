package com.ets.unpack.hub;

/** 外部插件接口：实现类编译进 dex 放到 files/plugins/ 即自动加载。 */
public interface IPlugin {
    void install(Hub hub);
    default String name() { return getClass().getSimpleName(); }
    default String version() { return ""; }
    default String description() { return ""; }
}
