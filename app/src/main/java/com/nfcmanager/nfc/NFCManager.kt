package com.nfcmanager.nfc

import android.app.Activity
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.NfcManager
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.IsoDep
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.util.Log
import com.nfcmanager.data.model.NFCData
import com.nfcmanager.data.model.NFCType
import java.nio.charset.Charset
import java.util.*

/**
 * NFC管理器
 * 负责NFC设备的检测、标签读取和状态管理
 */
class NFCManager(private val context: Context) {
    
    companion object {
        private const val TAG = "NFCManager"
        
        // NDEF记录类型
        private const val RTD_TEXT = "T"
        private const val RTD_URI = "U"
        private const val RTD_SMART_POSTER = "Sp"
        private const val RTD_ALTERNATIVE_CARRIER = "ac"
        private const val RTD_HANDOVER_CARRIER = "Hc"
        private const val RTD_HANDOVER_REQUEST = "Hr"
        private const val RTD_HANDOVER_SELECT = "Hs"
        private const val RTD_VCARD = "text/x-vCard"
    }
    
    private val nfcManager: NfcManager = context.getSystemService(Context.NFC_SERVICE) as NfcManager
    val nfcAdapter: NfcAdapter? = nfcManager.defaultAdapter
    
    /**
     * 检查设备是否支持NFC
     */
    fun isNFCSupported(): Boolean {
        return nfcAdapter != null
    }
    
    /**
     * 检查NFC是否已启用
     */
    fun isNFCEnabled(): Boolean {
        return nfcAdapter?.isEnabled == true
    }
    
    /**
     * 获取NFC状态
     */
    fun getNFCStatus(): NFCStatus {
        return when {
            !isNFCSupported() -> NFCStatus.NOT_SUPPORTED
            !isNFCEnabled() -> NFCStatus.DISABLED
            else -> NFCStatus.ENABLED
        }
    }
    
    /**
     * 从Tag读取NFC数据
     */
    fun readNFCTag(tag: Tag): NFCReadResult {
        return try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                readNDEFTag(ndef)
            } else {
                // 尝试读取非NDEF标签
                readNonNDEFTag(tag)
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取NFC标签失败", e)
            NFCReadResult.Error("读取失败: ${e.message}")
        }
    }
    
    /**
     * 读取NDEF格式标签
     */
    private fun readNDEFTag(ndef: Ndef): NFCReadResult {
        return try {
            ndef.connect()
            val ndefMessage = ndef.ndefMessage
            ndef.close()
            
            if (ndefMessage != null) {
                parseNDEFMessage(ndefMessage)
            } else {
                NFCReadResult.Success(NFCData(
                    content = "空标签",
                    type = NFCType.TEXT
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取NDEF标签失败", e)
            NFCReadResult.Error("读取NDEF标签失败: ${e.message}")
        }
    }
    
    /**
     * 读取非NDEF格式标签
     */
    private fun readNonNDEFTag(tag: Tag): NFCReadResult {
        return try {
            // 尝试识别标签类型
            val techList = tag.techList
            val tagType = when {
                techList.any { it.contains(NfcA::class.java.name) } -> "NFC-A"
                techList.any { it.contains(NfcB::class.java.name) } -> "NFC-B"
                techList.any { it.contains(NfcF::class.java.name) } -> "NFC-F"
                techList.any { it.contains(NfcV::class.java.name) } -> "NFC-V"
                techList.any { it.contains(MifareClassic::class.java.name) } -> "Mifare Classic"
                techList.any { it.contains(MifareUltralight::class.java.name) } -> "Mifare Ultralight"
                techList.any { it.contains(IsoDep::class.java.name) } -> "ISO-DEP"
                else -> "未知类型"
            }
            
            NFCReadResult.Success(NFCData(
                content = "非NDEF标签: $tagType",
                type = NFCType.UNKNOWN,
                rawData = tag.id
            ))
        } catch (e: Exception) {
            Log.e(TAG, "读取非NDEF标签失败", e)
            NFCReadResult.Error("读取标签失败: ${e.message}")
        }
    }
    
    /**
     * 解析NDEF消息
     */
    private fun parseNDEFMessage(ndefMessage: NdefMessage): NFCReadResult {
        val records = ndefMessage.records
        if (records.isEmpty()) {
            return NFCReadResult.Success(NFCData(
                content = "空NDEF消息",
                type = NFCType.TEXT
            ))
        }
        
        // 只处理第一个记录（简化处理）
        val record = records[0]
        return when {
            record.toUri() != null -> parseURIRecord(record)
            record.tnf == NdefRecord.TNF_WELL_KNOWN && Arrays.equals(record.type, NdefRecord.RTD_TEXT) -> 
                parseTextRecord(record)
            record.tnf == NdefRecord.TNF_MIME_MEDIA -> parseMimeRecord(record)
            else -> parseUnknownRecord(record)
        }
    }
    
    /**
     * 解析URI记录
     */
    private fun parseURIRecord(record: NdefRecord): NFCReadResult {
        val uri = record.toUri()
        return if (uri != null) {
            val content = uri.toString()
            val type = when {
                content.startsWith("http://") || content.startsWith("https://") -> NFCType.URL
                content.startsWith("tel:") -> NFCType.PHONE
                content.startsWith("mailto:") -> NFCType.EMAIL
                content.startsWith("geo:") -> NFCType.GEO
                else -> NFCType.URL
            }
            NFCReadResult.Success(NFCData(content = content, type = type))
        } else {
            NFCReadResult.Error("无法解析URI记录")
        }
    }
    
    /**
     * 解析文本记录
     */
    private fun parseTextRecord(record: NdefRecord): NFCReadResult {
        try {
            val payload = record.payload
            val textEncoding = if ((payload[0].toInt() and 0x80) == 0) "UTF-8" else "UTF-16"
            val languageCodeLength = payload[0].toInt() and 0x3F
            val text = String(
                payload,
                languageCodeLength + 1,
                payload.size - languageCodeLength - 1,
                Charset.forName(textEncoding)
            )
            return NFCReadResult.Success(NFCData(content = text, type = NFCType.TEXT))
        } catch (e: Exception) {
            Log.e(TAG, "解析文本记录失败", e)
            return NFCReadResult.Error("解析文本记录失败: ${e.message}")
        }
    }
    
    /**
     * 解析MIME记录
     */
    private fun parseMimeRecord(record: NdefRecord): NFCReadResult {
        return try {
            val mimeType = String(record.type, Charset.forName("US-ASCII"))
            val payload = record.payload
            val content = String(payload, Charset.forName("UTF-8"))
            
            val type = NFCType.fromMimeType(mimeType)
            NFCReadResult.Success(NFCData(content = content, type = type))
        } catch (e: Exception) {
            Log.e(TAG, "解析MIME记录失败", e)
            NFCReadResult.Error("解析MIME记录失败: ${e.message}")
        }
    }
    
    /**
     * 解析未知记录
     */
    private fun parseUnknownRecord(record: NdefRecord): NFCReadResult {
        return try {
            val payload = record.payload
            val content = String(payload, Charset.forName("UTF-8"))
            NFCReadResult.Success(NFCData(content = content, type = NFCType.UNKNOWN))
        } catch (e: Exception) {
            NFCReadResult.Success(NFCData(
                content = "无法解析的NDEF记录",
                type = NFCType.UNKNOWN
            ))
        }
    }
    
    /**
     * NFC读取结果
     */
    sealed class NFCReadResult {
        data class Success(val data: NFCData) : NFCReadResult()
        data class Error(val message: String) : NFCReadResult()
    }
    
    /**
     * NFC状态枚举
     */
    enum class NFCStatus {
        ENABLED,
        DISABLED,
        NOT_SUPPORTED
    }
}