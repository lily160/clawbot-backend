package com.github.wechat.cloudbot.modules.media.api;

/**
 * 文生图 / 文生视频网关。
 */
public interface MediaGateway {

    boolean canGenerateImage();

    boolean canGenerateVideo();

    byte[] generateImage(String prompt);

    /** @return 视频字节；时长等信息由调用方按实现约定处理 */
    GeneratedVideo generateVideo(String prompt);

    record GeneratedVideo(byte[] bytes, int playLengthMs) {}
}
