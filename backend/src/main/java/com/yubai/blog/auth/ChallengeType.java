package com.yubai.blog.auth;

/** L-7：人机验证 challenge 类型。 */
public enum ChallengeType {
    /** 仅工作量证明（常开，用户无感）。 */
    POW,
    /** 工作量证明 + 图形验证码（失败升级后）。 */
    IMAGE
}
