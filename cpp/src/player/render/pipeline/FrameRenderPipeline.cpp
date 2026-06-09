#include "libreshockwave/player/render/pipeline/FrameRenderPipeline.hpp"

#include <stdexcept>
#include <utility>

namespace libreshockwave::player::render::pipeline {

void FrameRenderPipeline::registerStep(std::shared_ptr<FrameRenderPipelineStep> step) {
    if (!step) {
        throw std::invalid_argument("FrameRenderPipeline step cannot be null");
    }
    steps_.push_back(std::move(step));
}

const std::vector<std::shared_ptr<FrameRenderPipelineStep>>& FrameRenderPipeline::steps() const {
    return steps_;
}

int FrameRenderPipeline::stepCount() const {
    return static_cast<int>(steps_.size());
}

FrameSnapshot FrameRenderPipeline::renderFrame(int frameNumber,
                                               int stageWidth,
                                               int stageHeight,
                                               int backgroundColor,
                                               std::shared_ptr<const bitmap::Bitmap> stageImage,
                                               std::string debugInfo) const {
    FrameRenderPipelineContext context(frameNumber,
                                       stageWidth,
                                       stageHeight,
                                       backgroundColor,
                                       std::move(stageImage),
                                       std::move(debugInfo));

    for (const auto& step : steps_) {
        step->execute(context);
    }

    if (!context.snapshot().has_value()) {
        throw std::runtime_error("Frame render pipeline did not produce a snapshot");
    }
    return *context.snapshot();
}

} // namespace libreshockwave::player::render::pipeline
