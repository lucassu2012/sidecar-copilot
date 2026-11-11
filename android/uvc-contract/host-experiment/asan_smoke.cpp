// Self-authored detector smoke test. No upstream or device code.
#include <iostream>
#include <string>
#include <thread>

int main(int argc, char** argv) {
    if (argc != 2) return 64;
    int* memory = new int[4]{17, 18, 19, 20};
    if (std::string(argv[1]) == "negative") {
        delete[] memory;
        volatile int witness = memory[0]; // Deliberate detector negative control.
        std::cout << "UNDETECTED " << witness << '\n';
        return 2;
    }
    int result = 0;
    std::thread worker([&] { result = memory[0] + memory[3]; });
    worker.join();
    delete[] memory;
    if (result != 37) return 3;
    std::cout << "ASAN_SMOKE_POSITIVE_PASS\n";
    return 0;
}
