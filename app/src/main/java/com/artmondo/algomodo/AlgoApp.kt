package com.artmondo.algomodo

import android.app.Application
import com.artmondo.algomodo.core.registry.GeneratorRegistry
import com.artmondo.algomodo.generators.animation.*
import com.artmondo.algomodo.generators.cellular.*
import com.artmondo.algomodo.generators.fractals.*
import com.artmondo.algomodo.generators.geometry.*
import com.artmondo.algomodo.generators.graphs.*
import com.artmondo.algomodo.generators.image.*
import com.artmondo.algomodo.generators.noise.*
import com.artmondo.algomodo.generators.plotter.*
import com.artmondo.algomodo.generators.text.*
import com.artmondo.algomodo.generators.voronoi.*
import com.artmondo.algomodo.generators.procedural.*
import com.artmondo.algomodo.generators.flux.*
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AlgoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        registerAllGenerators()
    }

    private fun registerAllGenerators() {
        // Animation (9)
        GeneratorRegistry.register(AttractorTrailsGenerator())
        GeneratorRegistry.register(CurlFluidGenerator())
        GeneratorRegistry.register(FlowFieldInkGenerator())
        GeneratorRegistry.register(FlowingParticlesGenerator())
        GeneratorRegistry.register(KaleidoscopeGenerator())
        GeneratorRegistry.register(OrbitalGenerator())
        GeneratorRegistry.register(PlasmaFeedbackGenerator())
        GeneratorRegistry.register(SprottQuadraticGenerator())
        GeneratorRegistry.register(WaveInterferenceGenerator())

        // Cellular (14)
        GeneratorRegistry.register(CellularAgeTrailsGenerator())
        GeneratorRegistry.register(BriansBrainGenerator())
        GeneratorRegistry.register(CrystalGrowthGenerator())
        GeneratorRegistry.register(CyclicCaGenerator())
        GeneratorRegistry.register(DlaGenerator())
        GeneratorRegistry.register(EdenGrowthGenerator())
        GeneratorRegistry.register(ElementaryCaGenerator())
        GeneratorRegistry.register(ForestFireGenerator())
        GeneratorRegistry.register(GameOfLifeGenerator())
        GeneratorRegistry.register(IsingModelGenerator())
        GeneratorRegistry.register(PercolationGenerator())
        GeneratorRegistry.register(ReactionDiffusionGenerator())
        GeneratorRegistry.register(SandpileGenerator())
        GeneratorRegistry.register(TuringPatternsGenerator())

        // Fractals (10)
        GeneratorRegistry.register(BurningShipGenerator())
        GeneratorRegistry.register(FractalFlamesGenerator())
        GeneratorRegistry.register(IfsBarnsleyGenerator())
        GeneratorRegistry.register(JuliaGenerator())
        GeneratorRegistry.register(MandelbrotGenerator())
        GeneratorRegistry.register(MultibrotGenerator())
        GeneratorRegistry.register(NewtonGenerator())
        GeneratorRegistry.register(OrbitTrapsGenerator())
        GeneratorRegistry.register(PythagorasTreeGenerator())
        GeneratorRegistry.register(RecursiveSubdivisionGenerator())
        GeneratorRegistry.register(StrangeAttractorDensityGenerator())

        // Geometry (13)
        GeneratorRegistry.register(ChladniGenerator())
        GeneratorRegistry.register(GravityPackingGenerator())
        GeneratorRegistry.register(HarmonicsGenerator())
        GeneratorRegistry.register(IslamicPatternsGenerator())
        GeneratorRegistry.register(MoireGenerator())
        GeneratorRegistry.register(RosettesGenerator())
        GeneratorRegistry.register(RubiksPermutationGenerator())
        GeneratorRegistry.register(SuperformulaGenerator())
        GeneratorRegistry.register(TruchetGenerator())
        GeneratorRegistry.register(LissajousGenerator())
        GeneratorRegistry.register(LSystemGenerator())
        GeneratorRegistry.register(MstWebGenerator())
        GeneratorRegistry.register(SpirographGenerator())

        // Graphs (11)
        GeneratorRegistry.register(GraphAnisotropicGenerator())
        GeneratorRegistry.register(GraphConstrainedDelaunayGenerator())
        GeneratorRegistry.register(GraphEcosystemsGenerator())
        GeneratorRegistry.register(GraphEulerTrailsGenerator())
        GeneratorRegistry.register(GraphGabrielGraphGenerator())
        GeneratorRegistry.register(GraphGeodesicGenerator())
        GeneratorRegistry.register(GraphKNearestNeighborGenerator())
        GeneratorRegistry.register(GraphLowPolyGenerator())
        GeneratorRegistry.register(GraphPlanarGenerator())
        GeneratorRegistry.register(GraphSteinerNetworksGenerator())
        GeneratorRegistry.register(GraphTessellationsGenerator())

        // Image (15)
        GeneratorRegistry.register(AsciiArtGenerator())
        GeneratorRegistry.register(ConvolutionGenerator())
        GeneratorRegistry.register(DataMoshGenerator())
        GeneratorRegistry.register(DistanceFieldGenerator())
        GeneratorRegistry.register(DitherImageGenerator())
        GeneratorRegistry.register(EdgeDetectGenerator())
        GeneratorRegistry.register(FeedbackLoopGenerator())
        GeneratorRegistry.register(GlitchTransformGenerator())
        GeneratorRegistry.register(HalftoneGenerator())
        GeneratorRegistry.register(LinoCutGenerator())
        GeneratorRegistry.register(LumaMeshGenerator())
        GeneratorRegistry.register(MosaicGenerator())
        GeneratorRegistry.register(OpticalFlowGenerator())
        GeneratorRegistry.register(PixelSortGenerator())
        GeneratorRegistry.register(StipplePortraitGenerator())

        // Noise (7)
        GeneratorRegistry.register(DomainWarpMarbleGenerator())
        GeneratorRegistry.register(FbmTerrainGenerator())
        GeneratorRegistry.register(NoiseDomainWarpGenerator())
        GeneratorRegistry.register(NoiseFbmGenerator())
        GeneratorRegistry.register(NoiseSimplexFieldGenerator())
        GeneratorRegistry.register(NoiseRidgedGenerator())
        GeneratorRegistry.register(NoiseTurbulenceGenerator())

        // Plotter (14)
        GeneratorRegistry.register(PlotterBezierRibbonGenerator())
        GeneratorRegistry.register(PlotterCirclePackingGenerator())
        GeneratorRegistry.register(PlotterContourLinesGenerator())
        GeneratorRegistry.register(PlotterContourTopoGenerator())
        GeneratorRegistry.register(PlotterGuillocheGenerator())
        GeneratorRegistry.register(PlotterHalftoneDotsGenerator())
        GeneratorRegistry.register(HatchingGenerator())
        GeneratorRegistry.register(PlotterMeanderMazeGenerator())
        GeneratorRegistry.register(PlotterOffsetPathsGenerator())
        GeneratorRegistry.register(PlotterPhyllotaxisGenerator())
        GeneratorRegistry.register(PlotterScribbleGenerator())
        GeneratorRegistry.register(StipplingGenerator())
        GeneratorRegistry.register(PlotterStreamlinesGenerator())
        GeneratorRegistry.register(PlotterTspGenerator())

        // Text (9)
        GeneratorRegistry.register(TextCodeGenerator())
        GeneratorRegistry.register(TextConcreteGenerator())
        GeneratorRegistry.register(TextGlyphsGenerator())
        GeneratorRegistry.register(TextGridGenerator())
        GeneratorRegistry.register(TextMatrixGenerator())
        GeneratorRegistry.register(TextNaiveHandwritingGenerator())
        GeneratorRegistry.register(TextPoemGenerator())
        GeneratorRegistry.register(TextProceduralCursiveGenerator())
        GeneratorRegistry.register(TextRewriteGenerator())

        // Voronoi (11)
        GeneratorRegistry.register(CentroidalVoronoiGenerator())
        GeneratorRegistry.register(VoronoiContoursGenerator())
        GeneratorRegistry.register(VoronoiCrackleGenerator())
        GeneratorRegistry.register(DelaunayMeshGenerator())
        GeneratorRegistry.register(VoronoiDepthGenerator())
        GeneratorRegistry.register(VoronoiFracturedGenerator())
        GeneratorRegistry.register(VoronoiNeighborBandsGenerator())
        GeneratorRegistry.register(VoronoiRidgesGenerator())
        GeneratorRegistry.register(VoronoiCellsGenerator())
        GeneratorRegistry.register(VoronoiMosaicGenerator())
        GeneratorRegistry.register(VoronoiWeightedGenerator())

        // Procedural (9)
        GeneratorRegistry.register(AudioReactiveGenerator())
        GeneratorRegistry.register(DisplacementGenerator())
        GeneratorRegistry.register(EdgeGlowGenerator())
        GeneratorRegistry.register(FeedbackSystemsGenerator())
        GeneratorRegistry.register(FieldParticleGenerator())
        GeneratorRegistry.register(InstancedGeometryGenerator())
        GeneratorRegistry.register(ParticleAdvectionGenerator())
        GeneratorRegistry.register(SdfRaymarchGenerator())
        GeneratorRegistry.register(WarpGenerator())

        // Flux (12)
        GeneratorRegistry.register(FluxMetaballs2dGenerator())
        GeneratorRegistry.register(FluxDisplacementMapGenerator())
        GeneratorRegistry.register(FluxDomainRepetitionGenerator())
        GeneratorRegistry.register(FluxSignalRingsGenerator())
        GeneratorRegistry.register(FluxWaveformStackerGenerator())
        GeneratorRegistry.register(FluxWireframeTerrainGenerator())
        GeneratorRegistry.register(FluxSimplexWarpGenerator())
        GeneratorRegistry.register(FluxInstancedScatterGenerator())
        GeneratorRegistry.register(FluxPerlinFlowGenerator())
        GeneratorRegistry.register(FluxTrailSystemGenerator())
        GeneratorRegistry.register(FluxFeedbackLoopGenerator())
        GeneratorRegistry.register(FluxPixelSortFeedbackGenerator())
    }
}
