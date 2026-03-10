package com.artmondo.algomodo.core.registry

import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.GeneratorFamily

object GeneratorRegistry {
    private val generators = mutableMapOf<String, Generator>()
    private val familyMap = mutableMapOf<String, MutableList<Generator>>()

    private val familyNames = mapOf(
        "animation" to "Animation",
        "cellular" to "Cellular Automata",
        "fractals" to "Fractals",
        "geometry" to "Geometry",
        "graphs" to "Graphs",
        "image" to "Image Processing",
        "noise" to "Noise",
        "plotter" to "Plotter",
        "text" to "Text",
        "voronoi" to "Voronoi"
    )

    private val familyDescriptions = mapOf(
        "animation" to "Flow fields, particles, plasma, attractors",
        "cellular" to "Cellular automata (Game of Life, Reaction Diffusion, etc.)",
        "fractals" to "Mandelbrot, Julia, Newton, IFS, Burning Ship, Multibrot, Orbit Traps, Fractal Flames, Strange Attractors",
        "geometry" to "Islamic patterns, Truchet, L-Systems, Spirograph, etc.",
        "graphs" to "Tessellations, Low-Poly, Ecosystems, Steiner Networks",
        "image" to "Pixel Sort, Halftone, ASCII, Glitch, etc. (require source image)",
        "noise" to "Simplex, FBM, Domain Warp, Turbulence, etc.",
        "plotter" to "Pen-plotter styles: stippling, hatching, contours, etc.",
        "text" to "Concrete poetry, typographic grid, digital rain, etc.",
        "voronoi" to "Voronoi cells, Delaunay, ridges, fractured, etc."
    )

    fun register(generator: Generator) {
        generators[generator.id] = generator
        familyMap.getOrPut(generator.family) { mutableListOf() }.add(generator)
    }

    fun get(id: String): Generator? = generators[id]

    fun allGenerators(): List<Generator> = generators.values.sortedBy { it.styleName }

    fun allFamilies(): List<GeneratorFamily> {
        return familyMap.keys.sorted().map { familyId ->
            GeneratorFamily(
                id = familyId,
                displayName = familyNames[familyId] ?: familyId.replaceFirstChar { it.uppercase() },
                description = familyDescriptions[familyId] ?: "",
                generators = familyMap[familyId]?.sortedBy { it.styleName } ?: emptyList()
            )
        }
    }

    fun generatorsInFamily(familyId: String): List<Generator> {
        return familyMap[familyId]?.sortedBy { it.styleName } ?: emptyList()
    }

    fun nonImageGenerators(): List<Generator> {
        return generators.values.filter { it.family != "image" }.sortedBy { it.styleName }
    }

    fun randomNonImageGenerator(): Generator? {
        val list = nonImageGenerators()
        return if (list.isEmpty()) null else list.random()
    }

    fun count(): Int = generators.size
}
