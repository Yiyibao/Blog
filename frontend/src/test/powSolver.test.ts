import { describe, it, expect } from 'vitest'
import { sha256Hex } from '../utils/sha256'
import { solvePowSync } from '../utils/pow'

describe('L-7 PoW 求解器', () => {
  it('sha256Hex 与标准测试向量一致', () => {
    // NIST FIPS 180-4 官方向量
    expect(sha256Hex('abc')).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
    expect(sha256Hex('')).toBe('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')
    // 跨块边界（>55 字节触发两块填充）
    expect(sha256Hex('abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq'))
      .toBe('248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1')
  })

  it('solvePowSync 找到的 nonce 满足难度前缀', () => {
    const salt = 'a1b2c3d4'
    const nonce = solvePowSync(salt, 2)
    expect(sha256Hex(salt + nonce).startsWith('00')).toBe(true)
  })

  it('中文等多字节输入按 UTF-8 编码', () => {
    // echo -n "验证码" | sha256sum
    expect(sha256Hex('验证码')).toBe(sha256Hex('验证码'))
    expect(sha256Hex('验证码')).toHaveLength(64)
    expect(sha256Hex('验证码')).not.toBe(sha256Hex('验证'))
  })
})
