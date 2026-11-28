package almond.debugger

import java.nio.charset.StandardCharsets

/** MurmurHash2 (32-bit) of the UTF-8 bytes of a string, as unsigned integer
  *
  * This is the hash JupyterLab and ipykernel use to name the files of cells (the `Murmur2` hash
  * method of `debugInfo` responses).
  */
object Murmur2 {

  def hash(s: String, seed: Long): Long = {
    val data   = s.getBytes(StandardCharsets.UTF_8)
    val m      = 0x5bd1e995
    val length = data.length
    var h      = seed.toInt ^ length
    val end    = length & ~3
    var i      = 0
    while (i < end) {
      var k = (data(i) & 0xff) |
        ((data(i + 1) & 0xff) << 8) |
        ((data(i + 2) & 0xff) << 16) |
        ((data(i + 3) & 0xff) << 24)
      k *= m
      k ^= k >>> 24
      k *= m
      h *= m
      h ^= k
      i += 4
    }
    val rem = length & 3
    if (rem == 3) h ^= (data(end + 2) & 0xff) << 16
    if (rem >= 2) h ^= (data(end + 1) & 0xff) << 8
    if (rem >= 1) {
      h ^= data(end) & 0xff
      h *= m
    }
    h ^= h >>> 13
    h *= m
    h ^= h >>> 15
    h.toLong & 0xffffffffL
  }

  /** The seed JupyterLab and ipykernel use */
  def defaultSeed: Long = 0xc70f6907L
}
