#pragma once

// Exact public custom-layer ABI shipped in MapLibre Android 13.4.1. It is
// header-only: MapLibre takes ownership of a CustomLayerHost pointer and calls
// these virtual methods from its own render pass. Deliberately not linked to
// libmaplibre here because the AAR embeds a static C++ runtime; Prefab correctly
// rejects a second STL-using shared library linked against it.

#include <array>

namespace mbgl {

class PaintParameters;

namespace style {

struct CustomLayerInitParameters {
    virtual ~CustomLayerInitParameters() = default;
};

struct CustomLayerRenderParameters {
    double width;
    double height;
    double latitude;
    double longitude;
    double zoom;
    double bearing;
    double pitch;
    double fieldOfView;
    std::array<double, 16> projectionMatrix;
    std::array<double, 16> nearClippedProjectionMatrix;

    CustomLayerRenderParameters(const PaintParameters&);
};

class CustomLayerHost {
public:
    virtual ~CustomLayerHost() = default;
    virtual void initialize(const CustomLayerInitParameters&) = 0;
    virtual void render(const CustomLayerRenderParameters&) = 0;
    virtual void contextLost() = 0;
    virtual void deinitialize() = 0;
};

} // namespace style
} // namespace mbgl
