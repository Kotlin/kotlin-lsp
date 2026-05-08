/**
 * See language-server/docs/release.md for details.
 */

const fs = require('fs');
const path = require('path');
// In both modes it is 5 ../ steps up to project root
const intellijVscodeDir = path.resolve(__dirname, '../../../../../language-server/intellij-vscode');
const bundleType = process.env.BUNDLE_TYPE || 'kotlin-server';
const devSymlink = process.env.DEV_SYMLINK === '1';

// In DEV_SYMLINK mode, kotlin-vscode/ source lives at __dirname/../.. (out/dev/ is directly inside it).
// In production builds, source files are staged alongside this script in __dirname.
const kotlinSrcDir = devSymlink ? path.resolve(__dirname, '..', '..') : __dirname;

applyPatch('package.json', path.join(kotlinSrcDir, 'package-patch-kotlin.json'), 'package.json');

if (bundleType === 'kotlin-server') {
    const target = path.join(__dirname, 'package-lock.json');
    const src = path.join(kotlinSrcDir, `package-lock-${bundleType}.json`);
    fs.rmSync(target, {force: true});
    if (devSymlink) {
        fs.symlinkSync(src, target);
    } else {
        fs.copyFileSync(src, target);
    }
    return;
}

if (!fs.existsSync(intellijVscodeDir)) return;

function merge(target, patch) {
    if (Array.isArray(target) && Array.isArray(patch)) {
        return [...target, ...patch];
    }

    if (isObject(target) && isObject(patch)) {
        const result = {...target};
        for (const key in patch) {
            if (key === 'scripts') {
                result[key] = mergeScripts(target[key], patch[key]);
            } else {
                result[key] = merge(target[key], patch[key]);
            }
        }
        return result;
    }

    // merge(["a", "b", "c"], {"1": "B"}) -> ["a", "B", "c"]
    if (Array.isArray(target) && isObject(patch)) {
        const result = [...target];
        for (const key in patch) {
            result[+key] = merge(target[key], patch[key]);
        }
        return result;
    }

    return patch;
}

function mergeScripts(target, patch) {
    const result = {...target};
    for (const key in patch) {
        result[key] = target?.[key] ? `${target[key]} && ${patch[key]}` : patch[key];
    }
    return result;
}

function isObject(value) {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function applyPatch(targetPath, patchPath, outputPath) {
    const target = JSON.parse(fs.readFileSync(targetPath, 'utf8'));
    const patch = JSON.parse(fs.readFileSync(patchPath, 'utf8'));

    const merged = merge(target, patch);

    fs.writeFileSync(outputPath, JSON.stringify(merged, null, 2), 'utf8');
    console.log(`Merging ${patchPath} to ${outputPath}`);
}

/**
 * Recursively copies IntelliJ related files into kotlin-vscode sources.
 * Missing source directories are ignored to keep the patch step resilient.
 * Existing files with the same name are overwritten by overlay versions.
 */
function copyOverlayDirectory(sourceDir, targetDir) {
    if (!fs.existsSync(sourceDir)) {
        return;
    }

    fs.mkdirSync(targetDir, {recursive: true});
    for (const entry of fs.readdirSync(sourceDir, {withFileTypes: true})) {
        const sourcePath = path.join(sourceDir, entry.name);
        const targetPath = path.join(targetDir, entry.name);
        if (entry.isDirectory()) {
            if (fs.existsSync(targetPath) && !fs.statSync(targetPath).isDirectory()) {
                fs.rmSync(targetPath, {recursive: true, force: true});
            }
            copyOverlayDirectory(sourcePath, targetPath);
            continue;
        }
        if (entry.isFile()) {
            if (fs.existsSync(targetPath) && fs.statSync(targetPath).isDirectory()) {
                fs.rmSync(targetPath, {recursive: true, force: true});
            }
            fs.copyFileSync(sourcePath, targetPath);
            fs.chmodSync(targetPath, fs.statSync(sourcePath).mode);
        }
    }
}

function patchPackageJson(patchFile) {
    applyPatch('package.json', path.join(intellijVscodeDir, patchFile), 'package.json');
}

function copyOverlayDir(subdir) {
    copyOverlayDirectory(path.join(intellijVscodeDir, subdir), path.join(__dirname, subdir));
}

function copyOverlayFile(src, dest) {
    const target = path.join(__dirname, dest);
    fs.mkdirSync(path.dirname(target), {recursive: true});
    fs.copyFileSync(path.join(intellijVscodeDir, src), target);
}

function symlinkOverlayDir(subdir) {
    symlinkOverlayDirectory(path.join(intellijVscodeDir, subdir), path.join(__dirname, subdir));
}

function symlinkOverlayFile(src, dest) {
    const target = path.join(__dirname, dest);
    const targetDir = path.dirname(target);
    const lstat = fs.lstatSync(targetDir, {throwIfNoEntry: false});
    if (lstat?.isSymbolicLink()) {
        throw new Error(`Refusing to overlay into symlinked directory (would mutate source): ${targetDir}`);
    }
    fs.mkdirSync(targetDir, {recursive: true});
    symlinkOverlay(path.join(intellijVscodeDir, src), target);
}

function symlinkOverlayDirectory(sourceDir, targetDir) {
    if (!fs.existsSync(sourceDir)) return;
    // Refuse to descend into a symlinked target dir: it would resolve into the
    // source tree, and writing entries inside would mutate community sources.
    const lstat = fs.lstatSync(targetDir, {throwIfNoEntry: false});
    if (lstat?.isSymbolicLink()) {
        throw new Error(`Refusing to overlay into symlinked directory (would mutate source): ${targetDir}`);
    }
    fs.mkdirSync(targetDir, {recursive: true});
    for (const entry of fs.readdirSync(sourceDir, {withFileTypes: true})) {
        const sourcePath = path.join(sourceDir, entry.name);
        const targetPath = path.join(targetDir, entry.name);
        if (entry.isDirectory()) {
            symlinkOverlayDirectory(sourcePath, targetPath);
        } else {
            symlinkOverlay(sourcePath, targetPath);
        }
    }
}

function symlinkOverlay(sourcePath, targetPath) {
    fs.rmSync(targetPath, {recursive: true, force: true});
    fs.symlinkSync(sourcePath, targetPath);
    console.log(`Symlinking ${sourcePath} -> ${targetPath}`);
}

require(path.join(intellijVscodeDir, 'apply-intellij-impl.js'))(
        bundleType,
        patchPackageJson,
        devSymlink ? symlinkOverlayDir : copyOverlayDir,
        devSymlink ? symlinkOverlayFile : copyOverlayFile
);
