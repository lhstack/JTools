package com.lhstack.tools.converter

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.util.xmlb.Converter
import com.lhstack.tools.plugins.PluginInfo
import org.apache.commons.lang3.StringUtils

class JsonConverter : Converter<HashMap<String, PluginInfo>>() {

    private val gson = Gson()
    override fun toString(value: HashMap<String, PluginInfo>): String? {
        return gson.toJson(value)
    }

    override fun fromString(value: String): HashMap<String, PluginInfo>? {
        if (StringUtils.isEmpty(value)) {
            return hashMapOf()
        }
        return gson.fromJson(value, object : TypeToken<HashMap<String, PluginInfo>>() {}.type)
    }
}