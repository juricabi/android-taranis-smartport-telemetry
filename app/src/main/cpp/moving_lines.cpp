#include <GLES3/gl3.h>
#include <android/log.h>
#include <jni.h>
#include "maplibre_custom_layer.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <vector>

namespace {

constexpr char kLogTag[] = "TelemetryMovingLines";
constexpr int kLineCount = 3;
constexpr double kPi = 3.14159265358979323846;
constexpr double kMaxMercatorLatitude = 85.0511287798066;

struct GeoPoint {
    double latitude = 0.0;
    double longitude = 0.0;
};

struct LineState {
    std::vector<GeoPoint> points;
    float width = 0.0f;
    std::uint32_t color = 0;
};

struct ModelState {
    GeoPoint point;
    float heading = 0.0f;
    float size = 0.0f;
    int width = 0;
    int height = 0;
    std::vector<std::uint32_t> pixels;
    std::uint64_t imageGeneration = 0;
    bool visible = false;
};

struct CameraState {
    GeoPoint target;
    double bearing = 0.0;
    bool valid = false;
};

struct SceneState {
    std::array<LineState, kLineCount> lines;
    ModelState model;
    CameraState camera;
    std::uint64_t generation = 0;
};

/*
 * JNI writes pending while markerStep is assembling a display tick. commit()
 * copies the whole scene under this one lock. The GL thread therefore sees
 * either the previous complete tick or the next complete tick, never a model
 * from one and a line endpoint from the other.
 */
struct SharedState {
    std::mutex mutex;
    SceneState pending;
    SceneState published;
    SceneState previous;
    bool hasPrevious = false;
};

struct StateHandle {
    explicit StateHandle(std::shared_ptr<SharedState> state_)
        : state(std::move(state_)) {}

    std::shared_ptr<SharedState> state;
};

struct ScreenPoint {
    double x;
    double y;
    bool valid;
};

struct LineVertex {
    float screenX;
    float screenY;
    float startX;
    float startY;
    float endX;
    float endY;
    float red;
    float green;
    float blue;
    float alpha;
    float radius;
};

struct ModelVertex {
    float screenX;
    float screenY;
    float textureX;
    float textureY;
};

GLuint compileShader(GLenum type, const char* source, const char* label) {
    const GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);

    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled == GL_TRUE) return shader;

    GLint length = 0;
    glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &length);
    std::vector<char> message(static_cast<std::size_t>(std::max(length, 1)));
    glGetShaderInfoLog(shader, length, nullptr, message.data());
    __android_log_print(
        ANDROID_LOG_ERROR, kLogTag, "%s shader: %s", label, message.data()
    );
    glDeleteShader(shader);
    return 0;
}

GLuint linkProgram(
    const char* vertexSource,
    const char* fragmentSource,
    const char* label
) {
    const GLuint vertexShader =
        compileShader(GL_VERTEX_SHADER, vertexSource, label);
    const GLuint fragmentShader =
        compileShader(GL_FRAGMENT_SHADER, fragmentSource, label);
    if (vertexShader == 0 || fragmentShader == 0) {
        if (vertexShader != 0) glDeleteShader(vertexShader);
        if (fragmentShader != 0) glDeleteShader(fragmentShader);
        return 0;
    }

    const GLuint program = glCreateProgram();
    glAttachShader(program, vertexShader);
    glAttachShader(program, fragmentShader);
    glLinkProgram(program);
    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);

    GLint linked = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (linked == GL_TRUE) return program;

    GLint length = 0;
    glGetProgramiv(program, GL_INFO_LOG_LENGTH, &length);
    std::vector<char> message(static_cast<std::size_t>(std::max(length, 1)));
    glGetProgramInfoLog(program, length, nullptr, message.data());
    __android_log_print(
        ANDROID_LOG_ERROR, kLogTag, "%s program: %s", label, message.data()
    );
    glDeleteProgram(program);
    return 0;
}

bool clipTest(double p, double q, double& first, double& last) {
    if (p == 0.0) return q >= 0.0;
    const double at = q / p;
    if (p < 0.0) {
        if (at > last) return false;
        if (at > first) first = at;
    } else {
        if (at < first) return false;
        if (at < last) last = at;
    }
    return true;
}

bool clipSegment(
    double left,
    double top,
    double right,
    double bottom,
    double& x0,
    double& y0,
    double& x1,
    double& y1
) {
    const double originalX = x0;
    const double originalY = y0;
    const double dx = x1 - x0;
    const double dy = y1 - y0;
    double first = 0.0;
    double last = 1.0;

    if (!clipTest(-dx, originalX - left, first, last) ||
        !clipTest(dx, right - originalX, first, last) ||
        !clipTest(-dy, originalY - top, first, last) ||
        !clipTest(dy, bottom - originalY, first, last)) {
        return false;
    }

    x0 = originalX + first * dx;
    y0 = originalY + first * dy;
    x1 = originalX + last * dx;
    y1 = originalY + last * dy;
    return true;
}

class MovingLinesHost final : public mbgl::style::CustomLayerHost {
public:
    explicit MovingLinesHost(std::shared_ptr<SharedState> state_)
        : state(std::move(state_)) {}

    void initialize(const mbgl::style::CustomLayerInitParameters&) override {
        static constexpr char lineVertexSource[] =
            "#version 300 es\n"
            "precision highp float;\n"
            "layout(location=0) in vec2 a_screen;\n"
            "layout(location=1) in vec2 a_start;\n"
            "layout(location=2) in vec2 a_end;\n"
            "layout(location=3) in vec4 a_color;\n"
            "layout(location=4) in float a_radius;\n"
            "uniform vec2 u_viewport;\n"
            "out highp vec2 v_screen;\n"
            "out highp vec2 v_start;\n"
            "out highp vec2 v_end;\n"
            "out lowp vec4 v_color;\n"
            "out highp float v_radius;\n"
            "void main() {\n"
            "  vec2 clip = vec2(a_screen.x * 2.0 / u_viewport.x - 1.0,"
            "                   1.0 - a_screen.y * 2.0 / u_viewport.y);\n"
            "  gl_Position = vec4(clip, 1.0, 1.0);\n"
            "  v_screen = a_screen; v_start = a_start; v_end = a_end;\n"
            "  v_color = a_color; v_radius = a_radius;\n"
            "}\n";
        static constexpr char lineFragmentSource[] =
            "#version 300 es\n"
            "precision highp float;\n"
            "in highp vec2 v_screen;\n"
            "in highp vec2 v_start;\n"
            "in highp vec2 v_end;\n"
            "in lowp vec4 v_color;\n"
            "in highp float v_radius;\n"
            "out lowp vec4 fragColor;\n"
            "void main() {\n"
            "  vec2 along = v_end - v_start;\n"
            "  float length2 = max(dot(along, along), 0.0001);\n"
            "  float at = clamp(dot(v_screen - v_start, along) / length2, 0.0, 1.0);\n"
            "  float distanceToLine = length(v_screen - (v_start + along * at));\n"
            "  float coverage = 1.0 - smoothstep(v_radius - 0.5, v_radius + 0.5,"
            "                                     distanceToLine);\n"
            "  if (coverage <= 0.0) discard;\n"
            "  fragColor = vec4(v_color.rgb, v_color.a * coverage);\n"
            "}\n";
        static constexpr char modelVertexSource[] =
            "#version 300 es\n"
            "precision highp float;\n"
            "layout(location=0) in vec2 a_screen;\n"
            "layout(location=1) in vec2 a_texture;\n"
            "uniform vec2 u_viewport;\n"
            "out highp vec2 v_texture;\n"
            "void main() {\n"
            "  vec2 clip = vec2(a_screen.x * 2.0 / u_viewport.x - 1.0,"
            "                   1.0 - a_screen.y * 2.0 / u_viewport.y);\n"
            "  gl_Position = vec4(clip, 1.0, 1.0);\n"
            "  v_texture = a_texture;\n"
            "}\n";
        static constexpr char modelFragmentSource[] =
            "#version 300 es\n"
            "precision highp float;\n"
            "uniform sampler2D u_model;\n"
            "in highp vec2 v_texture;\n"
            "out lowp vec4 fragColor;\n"
            "void main() {\n"
            "  vec4 model = texture(u_model, v_texture);\n"
            "  if (model.a <= 0.001) discard;\n"
            "  fragColor = model;\n"
            "}\n";

        lineProgram =
            linkProgram(lineVertexSource, lineFragmentSource, "line");
        if (lineProgram != 0) {
            lineViewportUniform = glGetUniformLocation(lineProgram, "u_viewport");
            glGenVertexArrays(1, &lineVertexArray);
            glGenBuffers(1, &lineVertexBuffer);
        }

        modelProgram =
            linkProgram(modelVertexSource, modelFragmentSource, "model");
        if (modelProgram != 0) {
            modelViewportUniform = glGetUniformLocation(modelProgram, "u_viewport");
            modelTextureUniform = glGetUniformLocation(modelProgram, "u_model");
            glGenVertexArrays(1, &modelVertexArray);
            glGenBuffers(1, &modelVertexBuffer);
            glGenTextures(1, &modelTexture);
            glBindTexture(GL_TEXTURE_2D, modelTexture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glBindTexture(GL_TEXTURE_2D, 0);
        }

    }

    void render(const mbgl::style::CustomLayerRenderParameters& parameters) override {
        if (parameters.width <= 0.0 || parameters.height <= 0.0) return;

        SceneState snapshot;
        {
            std::lock_guard<std::mutex> lock(state->mutex);
            snapshot = state->published;
            if (state->hasPrevious && snapshot.camera.valid &&
                state->previous.camera.valid &&
                shouldUsePreviousScene(parameters, snapshot, state->previous)) {
                snapshot = state->previous;
            }
        }

        std::vector<LineVertex> lineVertices;
        if (lineProgram != 0) {
            lineVertices.reserve(768);
            for (const auto& line : snapshot.lines) {
                appendLine(parameters, line, lineVertices);
            }
        }

        std::array<ModelVertex, 6> modelVertices{};
        const bool drawModel =
            modelProgram != 0 &&
            buildModelVertices(parameters, snapshot.model, modelVertices);
        if (lineVertices.empty() && !drawModel) return;

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFuncSeparate(
            GL_SRC_ALPHA,
            GL_ONE_MINUS_SRC_ALPHA,
            GL_ONE,
            GL_ONE_MINUS_SRC_ALPHA
        );

        if (!lineVertices.empty()) {
            drawLines(parameters, lineVertices);
        }
        // Drawn last in this same callback: every line terminates underneath
        // the very model from the scene snapshot used to build that line.
        if (drawModel) {
            drawModelTexture(parameters, snapshot.model, modelVertices);
        }

    }

    void contextLost() override {
        lineProgram = 0;
        lineVertexArray = 0;
        lineVertexBuffer = 0;
        lineViewportUniform = -1;
        modelProgram = 0;
        modelVertexArray = 0;
        modelVertexBuffer = 0;
        modelTexture = 0;
        modelViewportUniform = -1;
        modelTextureUniform = -1;
        uploadedImageGeneration = std::numeric_limits<std::uint64_t>::max();
    }

    void deinitialize() override {
        if (lineVertexBuffer != 0) glDeleteBuffers(1, &lineVertexBuffer);
        if (lineVertexArray != 0) glDeleteVertexArrays(1, &lineVertexArray);
        if (lineProgram != 0) glDeleteProgram(lineProgram);
        if (modelTexture != 0) glDeleteTextures(1, &modelTexture);
        if (modelVertexBuffer != 0) glDeleteBuffers(1, &modelVertexBuffer);
        if (modelVertexArray != 0) glDeleteVertexArrays(1, &modelVertexArray);
        if (modelProgram != 0) glDeleteProgram(modelProgram);
        contextLost();
    }

private:
    static double cameraDistanceMetres(
        const mbgl::style::CustomLayerRenderParameters& parameters,
        const CameraState& camera
    ) {
        const double middleLatitude =
            (parameters.latitude + camera.target.latitude) * 0.5 * kPi / 180.0;
        const double north =
            (parameters.latitude - camera.target.latitude) * kPi / 180.0 *
            6371008.8;
        double longitudeDifference = parameters.longitude - camera.target.longitude;
        while (longitudeDifference > 180.0) longitudeDifference -= 360.0;
        while (longitudeDifference < -180.0) longitudeDifference += 360.0;
        const double east = longitudeDifference * kPi / 180.0 * 6371008.8 *
            std::cos(middleLatitude);
        return std::hypot(north, east);
    }

    static double bearingDistance(double left, double right) {
        double difference = std::fmod(std::abs(left - right), 360.0);
        if (difference > 180.0) difference = 360.0 - difference;
        return difference;
    }

    static bool shouldUsePreviousScene(
        const mbgl::style::CustomLayerRenderParameters& parameters,
        const SceneState& current,
        const SceneState& previous
    ) {
        const double currentPosition =
            cameraDistanceMetres(parameters, current.camera);
        const double previousPosition =
            cameraDistanceMetres(parameters, previous.camera);

        // jumpTo exposes one of these targets, not an interpolated camera. A
        // small guard prevents a hand-dragged camera, which matches neither,
        // from resurrecting an older aircraft scene.
        if (std::min(currentPosition, previousPosition) > 0.25) return false;
        if (std::abs(currentPosition - previousPosition) > 0.0001) {
            return previousPosition < currentPosition;
        }

        // A model can turn while almost stationary. Then position cannot tell
        // the two transforms apart, but the render parameter's bearing can.
        return bearingDistance(parameters.bearing, previous.camera.bearing) <
            bearingDistance(parameters.bearing, current.camera.bearing);
    }

    static bool worldPoint(
        const mbgl::style::CustomLayerRenderParameters& parameters,
        const GeoPoint& point,
        double& x,
        double& y
    ) {
        if (!std::isfinite(point.latitude) || !std::isfinite(point.longitude)) {
            return false;
        }
        double longitude = point.longitude;
        while (longitude - parameters.longitude > 180.0) longitude -= 360.0;
        while (longitude - parameters.longitude < -180.0) longitude += 360.0;
        const double latitude =
            std::clamp(point.latitude, -kMaxMercatorLatitude, kMaxMercatorLatitude);
        const double sine = std::sin(latitude * kPi / 180.0);
        const double worldSize = 512.0 * std::exp2(parameters.zoom);
        x = (longitude + 180.0) / 360.0 * worldSize;
        y = (0.5 - std::log((1.0 + sine) / (1.0 - sine)) / (4.0 * kPi)) *
            worldSize;
        return std::isfinite(x) && std::isfinite(y);
    }

    static ScreenPoint projectWorld(
        const mbgl::style::CustomLayerRenderParameters& parameters,
        double x,
        double y
    ) {
        const auto& matrix = parameters.projectionMatrix;
        const double clipX = matrix[0] * x + matrix[4] * y + matrix[12];
        const double clipY = matrix[1] * x + matrix[5] * y + matrix[13];
        const double clipW = matrix[3] * x + matrix[7] * y + matrix[15];
        if (!std::isfinite(clipW) || clipW <= 0.0) return {0.0, 0.0, false};

        const double screenX = (clipX / clipW * 0.5 + 0.5) * parameters.width;
        const double screenY = (0.5 - clipY / clipW * 0.5) * parameters.height;
        return {
            screenX,
            screenY,
            std::isfinite(screenX) && std::isfinite(screenY)
        };
    }

    static ScreenPoint project(
        const mbgl::style::CustomLayerRenderParameters& parameters,
        const GeoPoint& point
    ) {
        double x = 0.0;
        double y = 0.0;
        if (!worldPoint(parameters, point, x, y)) return {0.0, 0.0, false};
        return projectWorld(parameters, x, y);
    }

    static void appendLine(
        const mbgl::style::CustomLayerRenderParameters& parameters,
        const LineState& line,
        std::vector<LineVertex>& vertices
    ) {
        if (line.points.size() < 2 || line.width <= 0.0f) return;

        const float alpha = static_cast<float>((line.color >> 24) & 0xffu) / 255.0f;
        if (alpha <= 0.0f) return;
        const float red = static_cast<float>((line.color >> 16) & 0xffu) / 255.0f;
        const float green = static_cast<float>((line.color >> 8) & 0xffu) / 255.0f;
        const float blue = static_cast<float>(line.color & 0xffu) / 255.0f;
        const float radius = std::max(line.width * 0.5f, 0.5f);
        const double outer = static_cast<double>(radius) + 1.25;

        ScreenPoint previous = project(parameters, line.points.front());
        for (std::size_t index = 1; index < line.points.size(); ++index) {
            const ScreenPoint next = project(parameters, line.points[index]);
            if (previous.valid && next.valid) {
                double x0 = previous.x;
                double y0 = previous.y;
                double x1 = next.x;
                double y1 = next.y;
                if (clipSegment(
                        -outer,
                        -outer,
                        parameters.width + outer,
                        parameters.height + outer,
                        x0,
                        y0,
                        x1,
                        y1
                    )) {
                    const double minX = std::max(-outer, std::min(x0, x1) - outer);
                    const double maxX =
                        std::min(parameters.width + outer, std::max(x0, x1) + outer);
                    const double minY = std::max(-outer, std::min(y0, y1) - outer);
                    const double maxY =
                        std::min(parameters.height + outer, std::max(y0, y1) + outer);

                    const auto add = [&](double x, double y) {
                        vertices.push_back(
                            {
                                static_cast<float>(x),
                                static_cast<float>(y),
                                static_cast<float>(x0),
                                static_cast<float>(y0),
                                static_cast<float>(x1),
                                static_cast<float>(y1),
                                red,
                                green,
                                blue,
                                alpha,
                                radius
                            }
                        );
                    };
                    add(minX, minY);
                    add(maxX, minY);
                    add(minX, maxY);
                    add(minX, maxY);
                    add(maxX, minY);
                    add(maxX, maxY);
                }
            }
            previous = next;
        }
    }

    static bool buildModelVertices(
        const mbgl::style::CustomLayerRenderParameters& parameters,
        const ModelState& model,
        std::array<ModelVertex, 6>& vertices
    ) {
        if (!model.visible || model.size <= 0.0f || model.width <= 0 ||
            model.height <= 0 || model.pixels.empty()) {
            return false;
        }

        double worldX = 0.0;
        double worldY = 0.0;
        if (!worldPoint(parameters, model.point, worldX, worldY)) return false;
        const ScreenPoint centre = projectWorld(parameters, worldX, worldY);
        if (!centre.valid) return false;

        const double heading =
            (std::isfinite(model.heading) ? model.heading : 0.0f) * kPi / 180.0;
        // A local Mercator direction projected by the same matrix gives the
        // icon its exact on-screen orientation, including camera rotation and
        // any future pitch, without maintaining a second camera-angle model.
        const ScreenPoint nose = projectWorld(
            parameters,
            worldX + std::sin(heading) * 32.0,
            worldY - std::cos(heading) * 32.0
        );
        if (!nose.valid) return false;

        double directionX = nose.x - centre.x;
        double directionY = nose.y - centre.y;
        const double directionLength =
            std::sqrt(directionX * directionX + directionY * directionY);
        if (!std::isfinite(directionLength) || directionLength < 1e-6) return false;
        directionX /= directionLength;
        directionY /= directionLength;
        const double rightX = -directionY;
        const double rightY = directionX;
        const double half = model.size * 0.5;

        const double topLeftX = centre.x - rightX * half + directionX * half;
        const double topLeftY = centre.y - rightY * half + directionY * half;
        const double topRightX = centre.x + rightX * half + directionX * half;
        const double topRightY = centre.y + rightY * half + directionY * half;
        const double bottomLeftX = centre.x - rightX * half - directionX * half;
        const double bottomLeftY = centre.y - rightY * half - directionY * half;
        const double bottomRightX = centre.x + rightX * half - directionX * half;
        const double bottomRightY = centre.y + rightY * half - directionY * half;

        const auto vertex = [](double x, double y, float u, float v) {
            return ModelVertex{
                static_cast<float>(x),
                static_cast<float>(y),
                u,
                v
            };
        };
        vertices = {
            vertex(topLeftX, topLeftY, 0.0f, 0.0f),
            vertex(topRightX, topRightY, 1.0f, 0.0f),
            vertex(bottomLeftX, bottomLeftY, 0.0f, 1.0f),
            vertex(bottomLeftX, bottomLeftY, 0.0f, 1.0f),
            vertex(topRightX, topRightY, 1.0f, 0.0f),
            vertex(bottomRightX, bottomRightY, 1.0f, 1.0f)
        };
        return true;
    }

    void drawLines(
        const mbgl::style::CustomLayerRenderParameters& parameters,
        const std::vector<LineVertex>& vertices
    ) {
        glUseProgram(lineProgram);
        glBindVertexArray(lineVertexArray);
        glBindBuffer(GL_ARRAY_BUFFER, lineVertexBuffer);
        glBufferData(
            GL_ARRAY_BUFFER,
            static_cast<GLsizeiptr>(vertices.size() * sizeof(LineVertex)),
            vertices.data(),
            GL_DYNAMIC_DRAW
        );

        constexpr GLsizei stride = sizeof(LineVertex);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(
            0,
            2,
            GL_FLOAT,
            GL_FALSE,
            stride,
            reinterpret_cast<void*>(offsetof(LineVertex, screenX))
        );
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(
            1,
            2,
            GL_FLOAT,
            GL_FALSE,
            stride,
            reinterpret_cast<void*>(offsetof(LineVertex, startX))
        );
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(
            2,
            2,
            GL_FLOAT,
            GL_FALSE,
            stride,
            reinterpret_cast<void*>(offsetof(LineVertex, endX))
        );
        glEnableVertexAttribArray(3);
        glVertexAttribPointer(
            3,
            4,
            GL_FLOAT,
            GL_FALSE,
            stride,
            reinterpret_cast<void*>(offsetof(LineVertex, red))
        );
        glEnableVertexAttribArray(4);
        glVertexAttribPointer(
            4,
            1,
            GL_FLOAT,
            GL_FALSE,
            stride,
            reinterpret_cast<void*>(offsetof(LineVertex, radius))
        );
        glUniform2f(
            lineViewportUniform,
            static_cast<float>(parameters.width),
            static_cast<float>(parameters.height)
        );
        glDrawArrays(GL_TRIANGLES, 0, static_cast<GLsizei>(vertices.size()));
    }

    void uploadModelTexture(const ModelState& model) {
        if (uploadedImageGeneration == model.imageGeneration) return;

        std::vector<std::uint8_t> rgba(model.pixels.size() * 4);
        for (std::size_t index = 0; index < model.pixels.size(); ++index) {
            const std::uint32_t argb = model.pixels[index];
            rgba[index * 4] = static_cast<std::uint8_t>((argb >> 16) & 0xffu);
            rgba[index * 4 + 1] = static_cast<std::uint8_t>((argb >> 8) & 0xffu);
            rgba[index * 4 + 2] = static_cast<std::uint8_t>(argb & 0xffu);
            rgba[index * 4 + 3] = static_cast<std::uint8_t>((argb >> 24) & 0xffu);
        }

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, modelTexture);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(
            GL_TEXTURE_2D,
            0,
            GL_RGBA,
            model.width,
            model.height,
            0,
            GL_RGBA,
            GL_UNSIGNED_BYTE,
            rgba.data()
        );
        uploadedImageGeneration = model.imageGeneration;
    }

    void drawModelTexture(
        const mbgl::style::CustomLayerRenderParameters& parameters,
        const ModelState& model,
        const std::array<ModelVertex, 6>& vertices
    ) {
        uploadModelTexture(model);
        glUseProgram(modelProgram);
        glBindVertexArray(modelVertexArray);
        glBindBuffer(GL_ARRAY_BUFFER, modelVertexBuffer);
        glBufferData(
            GL_ARRAY_BUFFER,
            static_cast<GLsizeiptr>(vertices.size() * sizeof(ModelVertex)),
            vertices.data(),
            GL_DYNAMIC_DRAW
        );

        constexpr GLsizei stride = sizeof(ModelVertex);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(
            0,
            2,
            GL_FLOAT,
            GL_FALSE,
            stride,
            reinterpret_cast<void*>(offsetof(ModelVertex, screenX))
        );
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(
            1,
            2,
            GL_FLOAT,
            GL_FALSE,
            stride,
            reinterpret_cast<void*>(offsetof(ModelVertex, textureX))
        );
        glUniform2f(
            modelViewportUniform,
            static_cast<float>(parameters.width),
            static_cast<float>(parameters.height)
        );
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, modelTexture);
        glUniform1i(modelTextureUniform, 0);
        glDrawArrays(GL_TRIANGLES, 0, static_cast<GLsizei>(vertices.size()));
    }

    std::shared_ptr<SharedState> state;
    GLuint lineProgram = 0;
    GLuint lineVertexArray = 0;
    GLuint lineVertexBuffer = 0;
    GLint lineViewportUniform = -1;
    GLuint modelProgram = 0;
    GLuint modelVertexArray = 0;
    GLuint modelVertexBuffer = 0;
    GLuint modelTexture = 0;
    GLint modelViewportUniform = -1;
    GLint modelTextureUniform = -1;
    std::uint64_t uploadedImageGeneration =
        std::numeric_limits<std::uint64_t>::max();
};

StateHandle* stateHandle(jlong value) {
    return reinterpret_cast<StateHandle*>(value);
}

} // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_juricabi_com_telemetry_maps_maplibre_MapLibreMovingLinesNative_create(
    JNIEnv* environment,
    jobject
) {
    auto state = std::make_shared<SharedState>();
    auto host = std::make_unique<MovingLinesHost>(state);
    auto handle = std::make_unique<StateHandle>(std::move(state));
    const jlong values[] = {
        reinterpret_cast<jlong>(host.release()),
        reinterpret_cast<jlong>(handle.release())
    };
    jlongArray result = environment->NewLongArray(2);
    if (result != nullptr) environment->SetLongArrayRegion(result, 0, 2, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_juricabi_com_telemetry_maps_maplibre_MapLibreMovingLinesNative_setLine(
    JNIEnv* environment,
    jobject,
    jlong handleValue,
    jint slot,
    jdoubleArray coordinates,
    jfloat width,
    jint color
) {
    StateHandle* handle = stateHandle(handleValue);
    if (handle == nullptr || slot < 0 || slot >= kLineCount || coordinates == nullptr) {
        return;
    }

    const jsize count = environment->GetArrayLength(coordinates);
    if ((count & 1) != 0) return;
    std::vector<jdouble> values(static_cast<std::size_t>(count));
    environment->GetDoubleArrayRegion(coordinates, 0, count, values.data());

    LineState line;
    line.width = width;
    line.color = static_cast<std::uint32_t>(color);
    line.points.reserve(static_cast<std::size_t>(count / 2));
    for (jsize index = 0; index < count; index += 2) {
        line.points.push_back({values[index], values[index + 1]});
    }

    std::lock_guard<std::mutex> lock(handle->state->mutex);
    handle->state->pending.lines[static_cast<std::size_t>(slot)] = std::move(line);
}

extern "C" JNIEXPORT void JNICALL
Java_juricabi_com_telemetry_maps_maplibre_MapLibreMovingLinesNative_clearLine(
    JNIEnv*,
    jobject,
    jlong handleValue,
    jint slot
) {
    StateHandle* handle = stateHandle(handleValue);
    if (handle == nullptr || slot < 0 || slot >= kLineCount) return;
    std::lock_guard<std::mutex> lock(handle->state->mutex);
    handle->state->pending.lines[static_cast<std::size_t>(slot)].points.clear();
}

extern "C" JNIEXPORT void JNICALL
Java_juricabi_com_telemetry_maps_maplibre_MapLibreMovingLinesNative_setModel(
    JNIEnv*,
    jobject,
    jlong handleValue,
    jdouble latitude,
    jdouble longitude,
    jfloat heading,
    jboolean visible
) {
    StateHandle* handle = stateHandle(handleValue);
    if (handle == nullptr) return;
    std::lock_guard<std::mutex> lock(handle->state->mutex);
    ModelState& model = handle->state->pending.model;
    model.point = {latitude, longitude};
    model.heading = heading;
    model.visible = visible == JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_juricabi_com_telemetry_maps_maplibre_MapLibreMovingLinesNative_setModelImage(
    JNIEnv* environment,
    jobject,
    jlong handleValue,
    jintArray pixels,
    jint width,
    jint height,
    jfloat size
) {
    StateHandle* handle = stateHandle(handleValue);
    if (handle == nullptr || pixels == nullptr || width <= 0 || height <= 0 ||
        !std::isfinite(size) || size <= 0.0f) {
        return;
    }
    const std::int64_t needed =
        static_cast<std::int64_t>(width) * static_cast<std::int64_t>(height);
    if (needed <= 0 || needed > std::numeric_limits<jsize>::max() ||
        environment->GetArrayLength(pixels) != static_cast<jsize>(needed)) {
        return;
    }

    std::vector<jint> values(static_cast<std::size_t>(needed));
    environment->GetIntArrayRegion(
        pixels, 0, static_cast<jsize>(needed), values.data()
    );
    std::vector<std::uint32_t> image(values.size());
    std::transform(
        values.begin(),
        values.end(),
        image.begin(),
        [](jint value) { return static_cast<std::uint32_t>(value); }
    );

    std::lock_guard<std::mutex> lock(handle->state->mutex);
    ModelState& model = handle->state->pending.model;
    model.width = width;
    model.height = height;
    model.size = size;
    model.pixels = std::move(image);
    model.imageGeneration =
        model.imageGeneration == std::numeric_limits<std::uint64_t>::max()
        ? 1
        : model.imageGeneration + 1;
}

extern "C" JNIEXPORT void JNICALL
Java_juricabi_com_telemetry_maps_maplibre_MapLibreMovingLinesNative_clearModel(
    JNIEnv*,
    jobject,
    jlong handleValue
) {
    StateHandle* handle = stateHandle(handleValue);
    if (handle == nullptr) return;
    std::lock_guard<std::mutex> lock(handle->state->mutex);
    handle->state->pending.model.visible = false;
}

extern "C" JNIEXPORT void JNICALL
Java_juricabi_com_telemetry_maps_maplibre_MapLibreMovingLinesNative_setCamera(
    JNIEnv*,
    jobject,
    jlong handleValue,
    jdouble latitude,
    jdouble longitude,
    jdouble bearing
) {
    StateHandle* handle = stateHandle(handleValue);
    if (handle == nullptr || !std::isfinite(latitude) ||
        !std::isfinite(longitude) || !std::isfinite(bearing)) {
        return;
    }
    std::lock_guard<std::mutex> lock(handle->state->mutex);
    CameraState& camera = handle->state->pending.camera;
    camera.target = {latitude, longitude};
    camera.bearing = bearing;
    camera.valid = true;
}

extern "C" JNIEXPORT void JNICALL
Java_juricabi_com_telemetry_maps_maplibre_MapLibreMovingLinesNative_commit(
    JNIEnv*,
    jobject,
    jlong handleValue
) {
    StateHandle* handle = stateHandle(handleValue);
    if (handle == nullptr) return;
    std::lock_guard<std::mutex> lock(handle->state->mutex);
    if (handle->state->published.generation != 0) {
        handle->state->previous = handle->state->published;
        handle->state->hasPrevious = true;
    }
    handle->state->pending.generation =
        handle->state->published.generation ==
            std::numeric_limits<std::uint64_t>::max()
        ? 1
        : handle->state->published.generation + 1;
    handle->state->published = handle->state->pending;
}

extern "C" JNIEXPORT void JNICALL
Java_juricabi_com_telemetry_maps_maplibre_MapLibreMovingLinesNative_destroyHandle(
    JNIEnv*,
    jobject,
    jlong handleValue
) {
    delete stateHandle(handleValue);
}

extern "C" JNIEXPORT void JNICALL
Java_juricabi_com_telemetry_maps_maplibre_MapLibreMovingLinesNative_abandonHost(
    JNIEnv*,
    jobject,
    jlong hostValue
) {
    delete reinterpret_cast<MovingLinesHost*>(hostValue);
}
