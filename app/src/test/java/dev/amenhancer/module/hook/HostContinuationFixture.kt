package dev.amenhancer.module.hook

interface HostContinuationFixture {
    fun getContext(): Bg.h
}
interface NoContextFixture {
    fun other(): String
}
