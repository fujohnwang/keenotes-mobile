import Foundation
import CryptoKit

/// Pure Swift implementation of Argon2id
/// Compatible with RFC 9106 and BouncyCastle implementation
class Argon2Swift {
    
    enum Argon2Type {
        case d   // Argon2d
        case i   // Argon2i
        case id  // Argon2id (recommended)
    }
    
    enum Argon2Version: UInt32 {
        case v10 = 0x10
        case v13 = 0x13
    }
    
    private let blockSize = 1024  // bytes per block
    private let syncPoints = 4    // number of sync points
    
    func hash(
        password: String,
        salt: Data,
        iterations: UInt32,
        memoryKB: UInt32,
        parallelism: UInt32,
        outputLength: Int,
        type: Argon2Type,
        version: Argon2Version
    ) throws -> Data {
        let passwordData = password.data(using: .utf8)!
        
        // Calculate memory blocks
        let memoryBlocks = max(8 * parallelism, memoryKB / UInt32(blockSize / 1024))
        let segmentLength = memoryBlocks / (parallelism * UInt32(syncPoints))
        let laneLength = segmentLength * UInt32(syncPoints)
        let totalBlocks = laneLength * parallelism
        
        // Initialize memory
        var memory = [[UInt64]](repeating: [UInt64](repeating: 0, count: 128), count: Int(totalBlocks))
        
        // Generate initial hash H0
        let h0 = generateH0(
            password: passwordData,
            salt: salt,
            parallelism: parallelism,
            outputLength: UInt32(outputLength),
            memoryKB: memoryKB,
            iterations: iterations,
            version: version,
            type: type
        )
        
        // Initialize first two blocks of each lane
        for lane in 0..<parallelism {
            let h0Prime0 = h0 + Data([0, 0, 0, 0]) + withUnsafeBytes(of: lane.littleEndian) { Data($0) }
            let block0 = hashLong(h0Prime0, outputLength: blockSize)
            memory[Int(lane * laneLength)] = bytesToBlock(block0)
            
            let h0Prime1 = h0 + Data([1, 0, 0, 0]) + withUnsafeBytes(of: lane.littleEndian) { Data($0) }
            let block1 = hashLong(h0Prime1, outputLength: blockSize)
            memory[Int(lane * laneLength + 1)] = bytesToBlock(block1)
        }
        
        // Main iterations
        for pass in 0..<iterations {
            for slice in 0..<UInt32(syncPoints) {
                for lane in 0..<parallelism {
                    fillSegment(
                        memory: &memory,
                        pass: pass,
                        lane: lane,
                        slice: slice,
                        totalBlocks: totalBlocks,
                        laneLength: laneLength,
                        segmentLength: segmentLength,
                        parallelism: parallelism,
                        type: type,
                        version: version
                    )
                }
            }
        }
        
        // Finalize: XOR last blocks of all lanes
        var finalBlock = memory[Int(laneLength - 1)]
        for lane in 1..<parallelism {
            let lastBlockIndex = Int((lane + 1) * laneLength - 1)
            for i in 0..<128 {
                finalBlock[i] ^= memory[lastBlockIndex][i]
            }
        }
        
        // Hash final block to get output
        let finalBytes = blockToBytes(finalBlock)
        return hashLong(finalBytes, outputLength: outputLength)
    }
    
    private func generateH0(
        password: Data,
        salt: Data,
        parallelism: UInt32,
        outputLength: UInt32,
        memoryKB: UInt32,
        iterations: UInt32,
        version: Argon2Version,
        type: Argon2Type
    ) -> Data {
        var input = Data()
        
        // Append all parameters as little-endian 32-bit integers
        input.append(contentsOf: withUnsafeBytes(of: parallelism.littleEndian) { Data($0) })
        input.append(contentsOf: withUnsafeBytes(of: outputLength.littleEndian) { Data($0) })
        input.append(contentsOf: withUnsafeBytes(of: memoryKB.littleEndian) { Data($0) })
        input.append(contentsOf: withUnsafeBytes(of: iterations.littleEndian) { Data($0) })
        input.append(contentsOf: withUnsafeBytes(of: version.rawValue.littleEndian) { Data($0) })
        
        let typeValue: UInt32 = type == .d ? 0 : (type == .i ? 1 : 2)
        input.append(contentsOf: withUnsafeBytes(of: typeValue.littleEndian) { Data($0) })
        
        // Password with length prefix
        input.append(contentsOf: withUnsafeBytes(of: UInt32(password.count).littleEndian) { Data($0) })
        input.append(password)
        
        // Salt with length prefix
        input.append(contentsOf: withUnsafeBytes(of: UInt32(salt.count).littleEndian) { Data($0) })
        input.append(salt)
        
        // No secret key
        input.append(contentsOf: withUnsafeBytes(of: UInt32(0).littleEndian) { Data($0) })
        
        // No associated data
        input.append(contentsOf: withUnsafeBytes(of: UInt32(0).littleEndian) { Data($0) })
        
        // BLAKE2b-512 hash
        return blake2b(input, outputLength: 64)
    }
    
    private func fillSegment(
        memory: inout [[UInt64]],
        pass: UInt32,
        lane: UInt32,
        slice: UInt32,
        totalBlocks: UInt32,
        laneLength: UInt32,
        segmentLength: UInt32,
        parallelism: UInt32,
        type: Argon2Type,
        version: Argon2Version
    ) {
        let startIndex: UInt32 = (pass == 0 && slice == 0) ? 2 : 0
        var prevOffset = (lane * laneLength + slice * segmentLength + startIndex - 1) % totalBlocks
        if startIndex == 0 && slice == 0 {
            prevOffset = lane * laneLength + laneLength - 1
        }
        
        for index in startIndex..<segmentLength {
            let currOffset = lane * laneLength + slice * segmentLength + index
            
            // Generate pseudo-random values for indexing
            let (j1, j2) = generateJ1J2(
                memory: memory,
                pass: pass,
                lane: lane,
                slice: slice,
                index: index,
                prevOffset: prevOffset,
                type: type,
                totalBlocks: totalBlocks,
                laneLength: laneLength,
                segmentLength: segmentLength,
                parallelism: parallelism
            )
            
            // Compute reference block position
            let refLane: UInt32
            if pass == 0 && slice == 0 {
                refLane = lane
            } else {
                refLane = j2 % parallelism
            }
            
            let refIndex = computeRefIndex(
                pass: pass,
                slice: slice,
                lane: lane,
                index: index,
                j1: j1,
                refLane: refLane,
                laneLength: laneLength,
                segmentLength: segmentLength,
                parallelism: parallelism
            )
            
            let refOffset = refLane * laneLength + refIndex
            
            // Compress
            memory[Int(currOffset)] = compress(
                prev: memory[Int(prevOffset)],
                ref: memory[Int(refOffset)],
                withXor: pass > 0 && version == .v13,
                current: pass > 0 ? memory[Int(currOffset)] : nil
            )
            
            prevOffset = currOffset
        }
    }
    
    private func generateJ1J2(
        memory: [[UInt64]],
        pass: UInt32,
        lane: UInt32,
        slice: UInt32,
        index: UInt32,
        prevOffset: UInt32,
        type: Argon2Type,
        totalBlocks: UInt32,
        laneLength: UInt32,
        segmentLength: UInt32,
        parallelism: UInt32
    ) -> (UInt32, UInt32) {
        let prevBlock = memory[Int(prevOffset)]
        
        if type == .d || (type == .id && pass > 0) || (type == .id && slice >= UInt32(syncPoints) / 2) {
            // Data-dependent: use previous block
            let j1 = UInt32(truncatingIfNeeded: prevBlock[0])
            let j2 = UInt32(truncatingIfNeeded: prevBlock[1])
            return (j1, j2)
        } else {
            // Data-independent: generate from counter
            var input = [UInt64](repeating: 0, count: 128)
            input[0] = UInt64(pass)
            input[1] = UInt64(lane)
            input[2] = UInt64(slice)
            input[3] = UInt64(totalBlocks)
            input[4] = UInt64(pass == 0 ? 1 : 2)  // iterations indicator
            input[5] = UInt64(type == .i ? 1 : 2)
            input[6] = UInt64(index / 128 + 1)
            
            let compressed = compressG(input, [UInt64](repeating: 0, count: 128))
            let idx = Int((index % 128) * 8 / 8)
            let j1 = UInt32(truncatingIfNeeded: compressed[idx])
            let j2 = UInt32(truncatingIfNeeded: compressed[idx] >> 32)
            return (j1, j2)
        }
    }
    
    private func computeRefIndex(
        pass: UInt32,
        slice: UInt32,
        lane: UInt32,
        index: UInt32,
        j1: UInt32,
        refLane: UInt32,
        laneLength: UInt32,
        segmentLength: UInt32,
        parallelism: UInt32
    ) -> UInt32 {
        var referenceAreaSize: UInt32
        
        if pass == 0 {
            if slice == 0 {
                referenceAreaSize = index - 1
            } else if refLane == lane {
                referenceAreaSize = slice * segmentLength + index - 1
            } else {
                referenceAreaSize = slice * segmentLength + (index == 0 ? 0 : index - 1)
            }
        } else {
            if refLane == lane {
                referenceAreaSize = laneLength - segmentLength + index - 1
            } else {
                referenceAreaSize = laneLength - segmentLength + (index == 0 ? 0 : index - 1)
            }
        }
        
        if referenceAreaSize == 0 {
            return 0
        }
        
        let j1_64 = UInt64(j1)
        let relativePosition = UInt32((j1_64 * j1_64) >> 32)
        let relativePosition2 = UInt32((UInt64(referenceAreaSize) * UInt64(relativePosition)) >> 32)
        let startPosition: UInt32
        
        if pass == 0 {
            startPosition = 0
        } else {
            startPosition = (slice + 1) * segmentLength % laneLength
        }
        
        return (startPosition + referenceAreaSize - relativePosition2) % laneLength
    }
    
    private func compress(prev: [UInt64], ref: [UInt64], withXor: Bool, current: [UInt64]?) -> [UInt64] {
        var result = compressG(prev, ref)
        
        if withXor, let curr = current {
            for i in 0..<128 {
                result[i] ^= curr[i]
            }
        }
        
        return result
    }
    
    private func compressG(_ x: [UInt64], _ y: [UInt64]) -> [UInt64] {
        var r = [UInt64](repeating: 0, count: 128)
        for i in 0..<128 {
            r[i] = x[i] ^ y[i]
        }
        
        // Apply Blake2b round function
        for i in 0..<8 {
            applyBlake2bRound(&r, v: [
                16 * i, 16 * i + 1, 16 * i + 2, 16 * i + 3,
                16 * i + 4, 16 * i + 5, 16 * i + 6, 16 * i + 7,
                16 * i + 8, 16 * i + 9, 16 * i + 10, 16 * i + 11,
                16 * i + 12, 16 * i + 13, 16 * i + 14, 16 * i + 15
            ])
        }
        
        for i in 0..<8 {
            applyBlake2bRound(&r, v: [
                2 * i, 2 * i + 1, 2 * i + 16, 2 * i + 17,
                2 * i + 32, 2 * i + 33, 2 * i + 48, 2 * i + 49,
                2 * i + 64, 2 * i + 65, 2 * i + 80, 2 * i + 81,
                2 * i + 96, 2 * i + 97, 2 * i + 112, 2 * i + 113
            ])
        }
        
        for i in 0..<128 {
            r[i] ^= x[i] ^ y[i]
        }
        
        return r
    }
    
    private func applyBlake2bRound(_ v: inout [UInt64], v indices: [Int]) {
        gb(&v, indices[0], indices[4], indices[8], indices[12])
        gb(&v, indices[1], indices[5], indices[9], indices[13])
        gb(&v, indices[2], indices[6], indices[10], indices[14])
        gb(&v, indices[3], indices[7], indices[11], indices[15])
        gb(&v, indices[0], indices[5], indices[10], indices[15])
        gb(&v, indices[1], indices[6], indices[11], indices[12])
        gb(&v, indices[2], indices[7], indices[8], indices[13])
        gb(&v, indices[3], indices[4], indices[9], indices[14])
    }
    
    private func gb(_ v: inout [UInt64], _ a: Int, _ b: Int, _ c: Int, _ d: Int) {
        v[a] = v[a] &+ v[b] &+ 2 &* (v[a] & 0xFFFFFFFF) &* (v[b] & 0xFFFFFFFF)
        v[d] = rotateRight(v[d] ^ v[a], 32)
        v[c] = v[c] &+ v[d] &+ 2 &* (v[c] & 0xFFFFFFFF) &* (v[d] & 0xFFFFFFFF)
        v[b] = rotateRight(v[b] ^ v[c], 24)
        v[a] = v[a] &+ v[b] &+ 2 &* (v[a] & 0xFFFFFFFF) &* (v[b] & 0xFFFFFFFF)
        v[d] = rotateRight(v[d] ^ v[a], 16)
        v[c] = v[c] &+ v[d] &+ 2 &* (v[c] & 0xFFFFFFFF) &* (v[d] & 0xFFFFFFFF)
        v[b] = rotateRight(v[b] ^ v[c], 63)
    }
    
    private func rotateRight(_ x: UInt64, _ n: Int) -> UInt64 {
        return (x >> n) | (x << (64 - n))
    }
    
    private func bytesToBlock(_ data: Data) -> [UInt64] {
        var block = [UInt64](repeating: 0, count: 128)
        data.withUnsafeBytes { (ptr: UnsafeRawBufferPointer) in
            for i in 0..<128 {
                block[i] = ptr.load(fromByteOffset: i * 8, as: UInt64.self).littleEndian
            }
        }
        return block
    }
    
    private func blockToBytes(_ block: [UInt64]) -> Data {
        var data = Data(count: 1024)
        data.withUnsafeMutableBytes { (ptr: UnsafeMutableRawBufferPointer) in
            for i in 0..<128 {
                ptr.storeBytes(of: block[i].littleEndian, toByteOffset: i * 8, as: UInt64.self)
            }
        }
        return data
    }
    
    /// BLAKE2b hash with variable output length
    private func blake2b(_ input: Data, outputLength: Int) -> Data {
        // Use CryptoKit's SHA512 as a fallback for BLAKE2b
        // For proper BLAKE2b, we implement it here
        return blake2bImpl(input, outputLength: outputLength)
    }
    
    /// Variable-length hash using BLAKE2b
    private func hashLong(_ input: Data, outputLength: Int) -> Data {
        if outputLength <= 64 {
            var prefixedInput = Data()
            prefixedInput.append(contentsOf: withUnsafeBytes(of: UInt32(outputLength).littleEndian) { Data($0) })
            prefixedInput.append(input)
            return blake2b(prefixedInput, outputLength: outputLength)
        }
        
        // For longer outputs, use BLAKE2b in a chain
        var result = Data()
        var v = blake2b(
            withUnsafeBytes(of: UInt32(outputLength).littleEndian) { Data($0) } + input,
            outputLength: 64
        )
        result.append(v[0..<32])
        
        let remaining = outputLength - 32
        let fullBlocks = (remaining - 1) / 32
        
        for _ in 0..<fullBlocks {
            v = blake2b(v, outputLength: 64)
            result.append(v[0..<32])
        }
        
        let lastBlockSize = remaining - fullBlocks * 32
        if lastBlockSize > 0 {
            v = blake2b(v, outputLength: lastBlockSize)
            result.append(v)
        }
        
        return result
    }
    
    /// BLAKE2b implementation
    private func blake2bImpl(_ message: Data, outputLength: Int) -> Data {
        let iv: [UInt64] = [
            0x6a09e667f3bcc908, 0xbb67ae8584caa73b,
            0x3c6ef372fe94f82b, 0xa54ff53a5f1d36f1,
            0x510e527fade682d1, 0x9b05688c2b3e6c1f,
            0x1f83d9abfb41bd6b, 0x5be0cd19137e2179
        ]
        
        let sigma: [[Int]] = [
            [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15],
            [14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3],
            [11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4],
            [7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8],
            [9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13],
            [2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9],
            [12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11],
            [13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10],
            [6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5],
            [10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0]
        ]
        
        var h = iv
        h[0] ^= 0x01010000 ^ UInt64(outputLength)
        
        var t: UInt64 = 0
        var paddedMessage = message
        let blockSize = 128
        
        // Pad message
        let remainder = message.count % blockSize
        if remainder != 0 || message.isEmpty {
            paddedMessage.append(Data(count: blockSize - remainder))
        }
        
        let numBlocks = paddedMessage.count / blockSize
        
        for i in 0..<numBlocks {
            let isLast = i == numBlocks - 1
            let blockStart = i * blockSize
            let block = paddedMessage[blockStart..<blockStart + blockSize]
            
            if isLast {
                t = UInt64(message.count)
            } else {
                t += UInt64(blockSize)
            }
            
            var v = h + iv
            v[12] ^= t
            v[14] ^= isLast ? ~UInt64(0) : 0
            
            var m = [UInt64](repeating: 0, count: 16)
            block.withUnsafeBytes { (ptr: UnsafeRawBufferPointer) in
                for j in 0..<16 {
                    m[j] = ptr.load(fromByteOffset: j * 8, as: UInt64.self).littleEndian
                }
            }
            
            for round in 0..<12 {
                let s = sigma[round % 10]
                blake2bG(&v, 0, 4, 8, 12, m[s[0]], m[s[1]])
                blake2bG(&v, 1, 5, 9, 13, m[s[2]], m[s[3]])
                blake2bG(&v, 2, 6, 10, 14, m[s[4]], m[s[5]])
                blake2bG(&v, 3, 7, 11, 15, m[s[6]], m[s[7]])
                blake2bG(&v, 0, 5, 10, 15, m[s[8]], m[s[9]])
                blake2bG(&v, 1, 6, 11, 12, m[s[10]], m[s[11]])
                blake2bG(&v, 2, 7, 8, 13, m[s[12]], m[s[13]])
                blake2bG(&v, 3, 4, 9, 14, m[s[14]], m[s[15]])
            }
            
            for j in 0..<8 {
                h[j] ^= v[j] ^ v[j + 8]
            }
        }
        
        var result = Data(count: outputLength)
        result.withUnsafeMutableBytes { (ptr: UnsafeMutableRawBufferPointer) in
            for i in 0..<min(8, (outputLength + 7) / 8) {
                let bytes = min(8, outputLength - i * 8)
                for j in 0..<bytes {
                    ptr[i * 8 + j] = UInt8((h[i] >> (j * 8)) & 0xFF)
                }
            }
        }
        
        return result
    }
    
    private func blake2bG(_ v: inout [UInt64], _ a: Int, _ b: Int, _ c: Int, _ d: Int, _ x: UInt64, _ y: UInt64) {
        v[a] = v[a] &+ v[b] &+ x
        v[d] = rotateRight(v[d] ^ v[a], 32)
        v[c] = v[c] &+ v[d]
        v[b] = rotateRight(v[b] ^ v[c], 24)
        v[a] = v[a] &+ v[b] &+ y
        v[d] = rotateRight(v[d] ^ v[a], 16)
        v[c] = v[c] &+ v[d]
        v[b] = rotateRight(v[b] ^ v[c], 63)
    }
}
