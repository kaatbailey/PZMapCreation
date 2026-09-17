const CELL_SIZE = 256;

const canvas = document.getElementById("map");
const ctx = canvas.getContext("2d");

const container = document.getElementById("map-container");

const worldXElement = document.getElementById("world-x");
const worldYElement = document.getElementById("world-y");

const cellXElement = document.getElementById("cell-x");
const cellYElement = document.getElementById("cell-y");

const localXElement = document.getElementById("local-x");
const localYElement = document.getElementById("local-y");

const clickedElement =
    document.getElementById("clicked-coordinate");

const statusElement =
    document.getElementById("status");

const image = new Image();

let mapInfo = null;

let zoom = 1.0;

let offsetX = 0;
let offsetY = 0;

let dragging = false;

let dragStartX = 0;
let dragStartY = 0;

let initialOffsetX = 0;
let initialOffsetY = 0;


/*
 * Load coordinate metadata first.
 */
fetch("generated/vanilla-world.json")
    .then(response => {
        if (!response.ok) {
            throw new Error(
                "Unable to load vanilla-world.json"
            );
        }

        return response.json();
    })
    .then(info => {

        mapInfo = info;

        image.onload = () => {

            canvas.width = image.width;
            canvas.height = image.height;

            fitMap();

            statusElement.textContent =
                `${image.width} × ${image.height}`;

            draw();
        };

        image.onerror = () => {

            statusElement.textContent =
                "Unable to load vanilla-world.png";
        };

        image.src = info.image;
    })
    .catch(error => {

        console.error(error);

        statusElement.textContent =
            "Failed to load map metadata";
    });


function fitMap() {

    const availableWidth =
        container.clientWidth;

    const availableHeight =
        container.clientHeight;

    const scaleX =
        availableWidth / canvas.width;

    const scaleY =
        availableHeight / canvas.height;

    zoom = Math.min(scaleX, scaleY) * 0.95;

    offsetX =
        (availableWidth - canvas.width * zoom) / 2;

    offsetY =
        (availableHeight - canvas.height * zoom) / 2;
}


function draw() {

    ctx.clearRect(
        0,
        0,
        canvas.width,
        canvas.height
    );

    ctx.drawImage(
        image,
        0,
        0
    );

    canvas.style.transform =
        `translate(${offsetX}px, ${offsetY}px) scale(${zoom})`;
}


/*
 * Convert screen coordinates into image pixels.
 */
function screenToImage(event) {

    const rect =
        container.getBoundingClientRect();

    const screenX =
        event.clientX - rect.left;

    const screenY =
        event.clientY - rect.top;

    const imageX =
        (screenX - offsetX) / zoom;

    const imageY =
        (screenY - offsetY) / zoom;

    return {
        x: imageX,
        y: imageY
    };
}


/*
 * Convert image coordinates into absolute PZ
 * world tile coordinates.
 */
function imageToWorld(imageX, imageY) {

    return {
        x:
            mapInfo.originX +
            imageX * mapInfo.pixelsPerTile,

        y:
            mapInfo.originY +
            imageY * mapInfo.pixelsPerTile
    };
}


/*
 * Convert absolute tile coordinate into
 * Build 42 cell + local coordinates.
 */
function worldToCell(worldX, worldY) {

    const x = Math.floor(worldX);
    const y = Math.floor(worldY);

    const cellX =
        Math.floor(x / CELL_SIZE);

    const cellY =
        Math.floor(y / CELL_SIZE);

    const localX =
        ((x % CELL_SIZE) + CELL_SIZE)
        % CELL_SIZE;

    const localY =
        ((y % CELL_SIZE) + CELL_SIZE)
        % CELL_SIZE;

    return {
        cellX,
        cellY,
        localX,
        localY
    };
}


/*
 * Update the coordinate display.
 */
function updateCoordinates(event) {

    if (!mapInfo) {
        return;
    }

    const imagePosition =
        screenToImage(event);

    if (
        imagePosition.x < 0 ||
        imagePosition.y < 0 ||
        imagePosition.x >= image.width ||
        imagePosition.y >= image.height
    ) {
        return;
    }

    const world =
        imageToWorld(
            imagePosition.x,
            imagePosition.y
        );

    const cell =
        worldToCell(
            world.x,
            world.y
        );

    worldXElement.textContent =
        Math.floor(world.x);

    worldYElement.textContent =
        Math.floor(world.y);

    cellXElement.textContent =
        cell.cellX;

    cellYElement.textContent =
        cell.cellY;

    localXElement.textContent =
        cell.localX;

    localYElement.textContent =
        cell.localY;
}


/*
 * Lock a coordinate by clicking.
 */
function clickCoordinate(event) {

    if (!mapInfo) {
        return;
    }

    const imagePosition =
        screenToImage(event);

    const world =
        imageToWorld(
            imagePosition.x,
            imagePosition.y
        );

    clickedElement.textContent =
        `Selected: ${Math.floor(world.x)}, ${Math.floor(world.y)}`;
}


/*
 * Mouse movement.
 */
container.addEventListener(
    "mousemove",
    event => {

        if (!dragging) {
            updateCoordinates(event);
        }
    }
);


/*
 * Start pan.
 */
container.addEventListener(
    "mousedown",
    event => {

        dragging = true;

        container.classList.add("dragging");

        dragStartX = event.clientX;
        dragStartY = event.clientY;

        initialOffsetX = offsetX;
        initialOffsetY = offsetY;
    }
);


/*
 * Continue pan.
 */
window.addEventListener(
    "mousemove",
    event => {

        if (!dragging) {
            return;
        }

        offsetX =
            initialOffsetX +
            (event.clientX - dragStartX);

        offsetY =
            initialOffsetY +
            (event.clientY - dragStartY);

        draw();
    }
);


/*
 * Finish pan.
 */
window.addEventListener(
    "mouseup",
    () => {

        dragging = false;

        container.classList.remove(
            "dragging"
        );
    }
);


/*
 * Click.
 */
container.addEventListener(
    "click",
    event => {

        /*
         * Ignore clicks that were actually
         * part of a drag.
         */
        if (
            Math.abs(event.clientX - dragStartX) > 4 ||
            Math.abs(event.clientY - dragStartY) > 4
        ) {
            return;
        }

        clickCoordinate(event);
    }
);


/*
 * Zoom around mouse position.
 */
container.addEventListener(
    "wheel",
    event => {

        event.preventDefault();

        const before =
            screenToImage(event);

        const zoomFactor =
            event.deltaY < 0
                ? 1.15
                : 1 / 1.15;

        const newZoom =
            Math.max(
                0.05,
                Math.min(
                    20,
                    zoom * zoomFactor
                )
            );

        zoom = newZoom;

        /*
         * Keep the image point under the
         * mouse stationary while zooming.
         */
        const rect =
            container.getBoundingClientRect();

        const mouseX =
            event.clientX - rect.left;

        const mouseY =
            event.clientY - rect.top;

        offsetX =
            mouseX - before.x * zoom;

        offsetY =
            mouseY - before.y * zoom;

        draw();
    },
    { passive: false }
);


window.addEventListener(
    "resize",
    () => {

        draw();
    }
);
