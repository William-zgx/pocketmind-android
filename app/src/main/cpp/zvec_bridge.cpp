#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <memory>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {

constexpr const char* kVersion = "zvec_bridge/1";
constexpr char kMagic[] = {'Z', 'V', 'E', 'C', 'J', 'N', 'I', '1'};
constexpr std::uint32_t kFormatVersion = 1;
constexpr std::uint32_t kMaxStringBytes = 1U << 20U;

struct Record {
    std::string domain;
    std::string id;
    std::string model_id;
    std::string source_hash;
    std::string privacy;
    std::string type;
    std::int64_t updated_at_millis = 0;
    std::vector<float> embedding;
    float norm = 0.0f;
};

struct SearchHit {
    const Record* record = nullptr;
    float score = 0.0f;
};

std::string MakeKey(const std::string& domain, const std::string& id, const std::string& model_id) {
    return std::to_string(domain.size()) + ":" + domain +
        std::to_string(id.size()) + ":" + id +
        std::to_string(model_id.size()) + ":" + model_id;
}

float VectorNorm(const std::vector<float>& values) {
    double sum = 0.0;
    for (float value : values) {
        sum += static_cast<double>(value) * static_cast<double>(value);
    }
    return static_cast<float>(std::sqrt(sum));
}

float CosineScore(const std::vector<float>& query, float query_norm, const Record& record) {
    if (query_norm <= 0.0f || record.norm <= 0.0f) {
        return 0.0f;
    }
    double dot = 0.0;
    const std::size_t count = std::min(query.size(), record.embedding.size());
    for (std::size_t index = 0; index < count; ++index) {
        dot += static_cast<double>(query[index]) * static_cast<double>(record.embedding[index]);
    }
    return static_cast<float>(dot / (static_cast<double>(query_norm) * static_cast<double>(record.norm)));
}

template <typename T>
void WritePod(std::ostream& output, const T& value) {
    output.write(reinterpret_cast<const char*>(&value), sizeof(T));
    if (!output.good()) {
        throw std::runtime_error("failed to write zvec snapshot");
    }
}

template <typename T>
T ReadPod(std::istream& input) {
    T value{};
    input.read(reinterpret_cast<char*>(&value), sizeof(T));
    if (!input.good()) {
        throw std::runtime_error("failed to read zvec snapshot");
    }
    return value;
}

void WriteString(std::ostream& output, const std::string& value) {
    const auto length = static_cast<std::uint32_t>(value.size());
    WritePod(output, length);
    output.write(value.data(), static_cast<std::streamsize>(value.size()));
    if (!output.good()) {
        throw std::runtime_error("failed to write zvec string");
    }
}

std::string ReadString(std::istream& input) {
    const std::uint32_t length = ReadPod<std::uint32_t>(input);
    if (length > kMaxStringBytes) {
        throw std::runtime_error("zvec string length is out of range");
    }
    std::string value(length, '\0');
    input.read(value.data(), static_cast<std::streamsize>(length));
    if (!input.good()) {
        throw std::runtime_error("failed to read zvec string");
    }
    return value;
}

class ZvecIndex {
public:
    ZvecIndex(std::string path, int dimension) : path_(std::move(path)), dimension_(dimension) {
        if (dimension_ <= 0) {
            throw std::invalid_argument("dimension must be positive");
        }
        LoadFromDisk();
    }

    void Upsert(Record record) {
        ValidateEmbedding(record.embedding);
        record.norm = VectorNorm(record.embedding);
        std::lock_guard<std::mutex> guard(mutex_);
        records_[MakeKey(record.domain, record.id, record.model_id)] = std::move(record);
        dirty_ = true;
    }

    std::optional<Record> Fetch(
        const std::string& domain,
        const std::string& id,
        const std::string& model_id
    ) const {
        std::lock_guard<std::mutex> guard(mutex_);
        const auto found = records_.find(MakeKey(domain, id, model_id));
        if (found == records_.end()) {
            return std::nullopt;
        }
        return found->second;
    }

    std::vector<SearchHit> Query(
        const std::optional<std::string>& domain,
        const std::optional<std::string>& model_id,
        const std::optional<std::string>& type,
        const std::vector<float>& query,
        int top_k
    ) const {
        ValidateEmbedding(query);
        if (top_k <= 0) {
            return {};
        }
        const float query_norm = VectorNorm(query);
        std::vector<SearchHit> hits;
        std::lock_guard<std::mutex> guard(mutex_);
        hits.reserve(std::min<std::size_t>(records_.size(), static_cast<std::size_t>(top_k)));
        for (const auto& entry : records_) {
            const Record& record = entry.second;
            if (domain.has_value() && record.domain != domain.value()) {
                continue;
            }
            if (model_id.has_value() && record.model_id != model_id.value()) {
                continue;
            }
            if (type.has_value() && record.type != type.value()) {
                continue;
            }
            const float score = CosineScore(query, query_norm, record);
            if (score > 0.0f && std::isfinite(score)) {
                hits.push_back(SearchHit{&record, score});
            }
        }
        const auto limit = std::min<std::size_t>(hits.size(), static_cast<std::size_t>(top_k));
        auto comparator = [](const SearchHit& left, const SearchHit& right) {
            if (left.score != right.score) {
                return left.score > right.score;
            }
            if (left.record->domain != right.record->domain) {
                return left.record->domain < right.record->domain;
            }
            if (left.record->id != right.record->id) {
                return left.record->id < right.record->id;
            }
            return left.record->model_id < right.record->model_id;
        };
        std::partial_sort(hits.begin(), hits.begin() + static_cast<std::ptrdiff_t>(limit), hits.end(), comparator);
        hits.resize(limit);
        return hits;
    }

    int Delete(const std::string& domain, const std::string& id, const std::optional<std::string>& model_id) {
        std::lock_guard<std::mutex> guard(mutex_);
        if (model_id.has_value()) {
            const int removed = records_.erase(MakeKey(domain, id, model_id.value())) > 0 ? 1 : 0;
            dirty_ = dirty_ || removed > 0;
            return removed;
        }
        int removed = 0;
        for (auto iterator = records_.begin(); iterator != records_.end();) {
            const Record& record = iterator->second;
            if (record.domain == domain && record.id == id) {
                iterator = records_.erase(iterator);
                ++removed;
            } else {
                ++iterator;
            }
        }
        dirty_ = dirty_ || removed > 0;
        return removed;
    }

    int DeleteForModel(const std::string& model_id, const std::optional<std::string>& domain) {
        std::lock_guard<std::mutex> guard(mutex_);
        int removed = 0;
        for (auto iterator = records_.begin(); iterator != records_.end();) {
            const Record& record = iterator->second;
            if (record.model_id == model_id && (!domain.has_value() || record.domain == domain.value())) {
                iterator = records_.erase(iterator);
                ++removed;
            } else {
                ++iterator;
            }
        }
        dirty_ = dirty_ || removed > 0;
        return removed;
    }

    int Clear(const std::optional<std::string>& domain) {
        std::lock_guard<std::mutex> guard(mutex_);
        int removed = 0;
        for (auto iterator = records_.begin(); iterator != records_.end();) {
            const Record& record = iterator->second;
            if (!domain.has_value() || record.domain == domain.value()) {
                iterator = records_.erase(iterator);
                ++removed;
            } else {
                ++iterator;
            }
        }
        dirty_ = dirty_ || removed > 0;
        return removed;
    }

    int Count(const std::optional<std::string>& domain) const {
        std::lock_guard<std::mutex> guard(mutex_);
        if (!domain.has_value()) {
            return static_cast<int>(records_.size());
        }
        int count = 0;
        for (const auto& entry : records_) {
            if (entry.second.domain == domain.value()) {
                ++count;
            }
        }
        return count;
    }

    int dimension() const {
        return dimension_;
    }

    void Flush() {
        std::lock_guard<std::mutex> guard(mutex_);
        if (!dirty_) {
            return;
        }
        SaveToDiskLocked();
        dirty_ = false;
    }

private:
    void ValidateEmbedding(const std::vector<float>& values) const {
        if (values.size() != static_cast<std::size_t>(dimension_)) {
            throw std::invalid_argument("embedding dimension mismatch");
        }
    }

    void LoadFromDisk() {
        if (path_.empty()) {
            return;
        }
        std::ifstream input(path_, std::ios::binary);
        if (!input.good()) {
            return;
        }
        char magic[sizeof(kMagic)] = {};
        input.read(magic, static_cast<std::streamsize>(sizeof(kMagic)));
        if (!input.good() || !std::equal(magic, magic + sizeof(kMagic), kMagic)) {
            throw std::runtime_error("zvec snapshot magic mismatch");
        }
        const std::uint32_t format_version = ReadPod<std::uint32_t>(input);
        if (format_version != kFormatVersion) {
            throw std::runtime_error("zvec snapshot format version mismatch");
        }
        const std::int32_t dimension = ReadPod<std::int32_t>(input);
        if (dimension != dimension_) {
            throw std::runtime_error("zvec snapshot dimension mismatch");
        }
        const std::uint32_t count = ReadPod<std::uint32_t>(input);
        for (std::uint32_t index = 0; index < count; ++index) {
            Record record;
            record.domain = ReadString(input);
            record.id = ReadString(input);
            record.model_id = ReadString(input);
            record.source_hash = ReadString(input);
            record.privacy = ReadString(input);
            record.type = ReadString(input);
            record.updated_at_millis = ReadPod<std::int64_t>(input);
            record.embedding.resize(static_cast<std::size_t>(dimension_));
            input.read(
                reinterpret_cast<char*>(record.embedding.data()),
                static_cast<std::streamsize>(sizeof(float) * record.embedding.size())
            );
            if (!input.good()) {
                throw std::runtime_error("failed to read zvec embedding");
            }
            record.norm = VectorNorm(record.embedding);
            records_[MakeKey(record.domain, record.id, record.model_id)] = std::move(record);
        }
    }

    void SaveToDiskLocked() const {
        if (path_.empty()) {
            return;
        }
        const std::string temp_path = path_ + ".tmp";
        {
            std::ofstream output(temp_path, std::ios::binary | std::ios::trunc);
            if (!output.good()) {
                throw std::runtime_error("failed to open zvec snapshot for writing");
            }
            output.write(kMagic, static_cast<std::streamsize>(sizeof(kMagic)));
            WritePod(output, kFormatVersion);
            WritePod(output, static_cast<std::int32_t>(dimension_));
            WritePod(output, static_cast<std::uint32_t>(records_.size()));
            for (const auto& entry : records_) {
                const Record& record = entry.second;
                WriteString(output, record.domain);
                WriteString(output, record.id);
                WriteString(output, record.model_id);
                WriteString(output, record.source_hash);
                WriteString(output, record.privacy);
                WriteString(output, record.type);
                WritePod(output, record.updated_at_millis);
                output.write(
                    reinterpret_cast<const char*>(record.embedding.data()),
                    static_cast<std::streamsize>(sizeof(float) * record.embedding.size())
                );
                if (!output.good()) {
                    throw std::runtime_error("failed to write zvec embedding");
                }
            }
        }
        if (std::rename(temp_path.c_str(), path_.c_str()) != 0) {
            std::remove(temp_path.c_str());
            throw std::runtime_error("failed to publish zvec snapshot");
        }
    }

    const std::string path_;
    const int dimension_;
    mutable std::mutex mutex_;
    std::unordered_map<std::string, Record> records_;
    bool dirty_ = false;
};

void ThrowJava(JNIEnv* env, const char* class_name, const std::string& message) {
    jclass clazz = env->FindClass(class_name);
    if (clazz != nullptr) {
        env->ThrowNew(clazz, message.c_str());
    }
}

ZvecIndex* FromHandle(JNIEnv* env, jlong handle) {
    if (handle == 0) {
        ThrowJava(env, "java/lang/IllegalStateException", "zvec native handle is closed");
        return nullptr;
    }
    return reinterpret_cast<ZvecIndex*>(handle);
}

std::string ToString(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return "";
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return "";
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::optional<std::string> ToOptionalString(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return std::nullopt;
    }
    return ToString(env, value);
}

std::vector<float> ToVector(JNIEnv* env, jfloatArray values, int dimension) {
    if (values == nullptr) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "embedding must not be null");
        return {};
    }
    const jsize length = env->GetArrayLength(values);
    if (length != dimension) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "embedding dimension mismatch");
        return {};
    }
    std::vector<float> result(static_cast<std::size_t>(length));
    env->GetFloatArrayRegion(values, 0, length, result.data());
    return result;
}

jfloatArray ToFloatArray(JNIEnv* env, const std::vector<float>& values) {
    jfloatArray array = env->NewFloatArray(static_cast<jsize>(values.size()));
    if (array == nullptr) {
        return nullptr;
    }
    env->SetFloatArrayRegion(array, 0, static_cast<jsize>(values.size()), values.data());
    return array;
}

jobject ToRecordRow(JNIEnv* env, const Record& record) {
    jclass row_class = env->FindClass("com/bytedance/zgx/pocketmind/storage/ZvecNativeRecordRow");
    if (row_class == nullptr) {
        return nullptr;
    }
    jmethodID constructor = env->GetMethodID(
        row_class,
        "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;J[F)V"
    );
    if (constructor == nullptr) {
        return nullptr;
    }
    jstring domain = env->NewStringUTF(record.domain.c_str());
    jstring id = env->NewStringUTF(record.id.c_str());
    jstring model_id = env->NewStringUTF(record.model_id.c_str());
    jstring source_hash = env->NewStringUTF(record.source_hash.c_str());
    jstring privacy = env->NewStringUTF(record.privacy.c_str());
    jstring type = env->NewStringUTF(record.type.c_str());
    jfloatArray embedding = ToFloatArray(env, record.embedding);
    jobject row = env->NewObject(
        row_class,
        constructor,
        domain,
        id,
        model_id,
        source_hash,
        privacy,
        type,
        static_cast<jlong>(record.updated_at_millis),
        embedding
    );
    env->DeleteLocalRef(domain);
    env->DeleteLocalRef(id);
    env->DeleteLocalRef(model_id);
    env->DeleteLocalRef(source_hash);
    env->DeleteLocalRef(privacy);
    env->DeleteLocalRef(type);
    env->DeleteLocalRef(embedding);
    return row;
}

jobject ToHitRow(JNIEnv* env, const SearchHit& hit) {
    jclass hit_class = env->FindClass("com/bytedance/zgx/pocketmind/storage/ZvecNativeHitRow");
    if (hit_class == nullptr) {
        return nullptr;
    }
    jmethodID constructor = env->GetMethodID(
        hit_class,
        "<init>",
        "(Lcom/bytedance/zgx/pocketmind/storage/ZvecNativeRecordRow;F)V"
    );
    if (constructor == nullptr || hit.record == nullptr) {
        return nullptr;
    }
    jobject record_row = ToRecordRow(env, *hit.record);
    jobject hit_row = env->NewObject(hit_class, constructor, record_row, static_cast<jfloat>(hit.score));
    env->DeleteLocalRef(record_row);
    return hit_row;
}

jobjectArray EmptyHitArray(JNIEnv* env) {
    jclass hit_class = env->FindClass("com/bytedance/zgx/pocketmind/storage/ZvecNativeHitRow");
    if (hit_class == nullptr) {
        return nullptr;
    }
    return env->NewObjectArray(0, hit_class, nullptr);
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_bytedance_zgx_pocketmind_storage_ZvecNativeStore_nativeVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF(kVersion);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_bytedance_zgx_pocketmind_storage_ZvecNativeStore_nativeCreate(
    JNIEnv* env,
    jobject,
    jstring storage_path,
    jint dimension
) {
    try {
        auto index = std::make_unique<ZvecIndex>(ToString(env, storage_path), static_cast<int>(dimension));
        return reinterpret_cast<jlong>(index.release());
    } catch (const std::invalid_argument& error) {
        ThrowJava(env, "java/lang/IllegalArgumentException", error.what());
        return 0;
    } catch (const std::exception& error) {
        ThrowJava(env, "java/io/IOException", error.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_bytedance_zgx_pocketmind_storage_ZvecNativeStore_nativeClose(JNIEnv* env, jobject, jlong handle) {
    ZvecIndex* index = FromHandle(env, handle);
    if (index == nullptr) {
        return;
    }
    delete index;
}

extern "C" JNIEXPORT void JNICALL
Java_com_bytedance_zgx_pocketmind_storage_ZvecNativeStore_nativeFlush(JNIEnv* env, jobject, jlong handle) {
    try {
        ZvecIndex* index = FromHandle(env, handle);
        if (index != nullptr) {
            index->Flush();
        }
    } catch (const std::exception& error) {
        ThrowJava(env, "java/io/IOException", error.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_bytedance_zgx_pocketmind_storage_ZvecNativeStore_nativeUpsert(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring domain,
    jstring id,
    jstring model_id,
    jstring source_hash,
    jstring privacy,
    jstring type,
    jlong updated_at_millis,
    jfloatArray embedding
) {
    try {
        ZvecIndex* index = FromHandle(env, handle);
        if (index == nullptr) {
            return;
        }
        Record record;
        record.domain = ToString(env, domain);
        record.id = ToString(env, id);
        record.model_id = ToString(env, model_id);
        record.source_hash = ToString(env, source_hash);
        record.privacy = ToString(env, privacy);
        record.type = ToString(env, type);
        record.updated_at_millis = static_cast<std::int64_t>(updated_at_millis);
        record.embedding = ToVector(env, embedding, index->dimension());
        if (env->ExceptionCheck()) {
            return;
        }
        index->Upsert(std::move(record));
    } catch (const std::invalid_argument& error) {
        ThrowJava(env, "java/lang/IllegalArgumentException", error.what());
    } catch (const std::exception& error) {
        ThrowJava(env, "java/lang/IllegalStateException", error.what());
    }
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_bytedance_zgx_pocketmind_storage_ZvecNativeStore_nativeFetch(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring domain,
    jstring id,
    jstring model_id
) {
    try {
        ZvecIndex* index = FromHandle(env, handle);
        if (index == nullptr) {
            return nullptr;
        }
        const auto record = index->Fetch(ToString(env, domain), ToString(env, id), ToString(env, model_id));
        if (!record.has_value()) {
            return nullptr;
        }
        return ToRecordRow(env, record.value());
    } catch (const std::exception& error) {
        ThrowJava(env, "java/lang/IllegalStateException", error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_bytedance_zgx_pocketmind_storage_ZvecNativeStore_nativeQuery(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring domain,
    jstring model_id,
    jstring type,
    jfloatArray embedding,
    jint top_k
) {
    try {
        ZvecIndex* index = FromHandle(env, handle);
        if (index == nullptr || top_k <= 0) {
            return EmptyHitArray(env);
        }
        std::vector<float> query = ToVector(env, embedding, index->dimension());
        if (env->ExceptionCheck()) {
            return nullptr;
        }
        const auto hits = index->Query(
            ToOptionalString(env, domain),
            ToOptionalString(env, model_id),
            ToOptionalString(env, type),
            query,
            static_cast<int>(top_k)
        );
        jclass hit_class = env->FindClass("com/bytedance/zgx/pocketmind/storage/ZvecNativeHitRow");
        if (hit_class == nullptr) {
            return nullptr;
        }
        jobjectArray array = env->NewObjectArray(static_cast<jsize>(hits.size()), hit_class, nullptr);
        if (array == nullptr) {
            return nullptr;
        }
        for (std::size_t index_in_hits = 0; index_in_hits < hits.size(); ++index_in_hits) {
            jobject hit_row = ToHitRow(env, hits[index_in_hits]);
            env->SetObjectArrayElement(array, static_cast<jsize>(index_in_hits), hit_row);
            env->DeleteLocalRef(hit_row);
        }
        return array;
    } catch (const std::invalid_argument& error) {
        ThrowJava(env, "java/lang/IllegalArgumentException", error.what());
        return nullptr;
    } catch (const std::exception& error) {
        ThrowJava(env, "java/lang/IllegalStateException", error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bytedance_zgx_pocketmind_storage_ZvecNativeStore_nativeDelete(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring domain,
    jstring id,
    jstring model_id
) {
    try {
        ZvecIndex* index = FromHandle(env, handle);
        if (index == nullptr) {
            return 0;
        }
        return static_cast<jint>(index->Delete(ToString(env, domain), ToString(env, id), ToOptionalString(env, model_id)));
    } catch (const std::exception& error) {
        ThrowJava(env, "java/lang/IllegalStateException", error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bytedance_zgx_pocketmind_storage_ZvecNativeStore_nativeDeleteForModel(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring model_id,
    jstring domain
) {
    try {
        ZvecIndex* index = FromHandle(env, handle);
        if (index == nullptr) {
            return 0;
        }
        return static_cast<jint>(index->DeleteForModel(ToString(env, model_id), ToOptionalString(env, domain)));
    } catch (const std::exception& error) {
        ThrowJava(env, "java/lang/IllegalStateException", error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bytedance_zgx_pocketmind_storage_ZvecNativeStore_nativeClear(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring domain
) {
    try {
        ZvecIndex* index = FromHandle(env, handle);
        if (index == nullptr) {
            return 0;
        }
        return static_cast<jint>(index->Clear(ToOptionalString(env, domain)));
    } catch (const std::exception& error) {
        ThrowJava(env, "java/lang/IllegalStateException", error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bytedance_zgx_pocketmind_storage_ZvecNativeStore_nativeCount(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring domain
) {
    try {
        ZvecIndex* index = FromHandle(env, handle);
        if (index == nullptr) {
            return 0;
        }
        return static_cast<jint>(index->Count(ToOptionalString(env, domain)));
    } catch (const std::exception& error) {
        ThrowJava(env, "java/lang/IllegalStateException", error.what());
        return 0;
    }
}
