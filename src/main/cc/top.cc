#include <verilated.h>
#include <iostream>
#include <vector>
#include <sstream>
#include <iomanip>
#include <fstream>

#if VM_TRACE
#include <verilated_vcd_c.h> // Trace file format header
#endif

#include "mm.h"
#include "rvcsim/cpu.h"

using namespace std;

vluint64_t main_time = 0; // Current simulation time
                          // This is a 64-bit integer to reduce wrap over issues and
                          // allow modulus.  You can also use a double, if you wish.

double sc_time_stamp()
{                     // Called by $time in Verilog
    return main_time; // converts to double, to match
                      // what SystemC does
}

VTile *top; // target design
#ifdef VM_TRACE
VerilatedVcdC *tfp;
#endif
mm_magic_t *mem; // target memory
Cpu *cpu;

// TODO Provide command-line options like vcd filename, timeout count, etc.
const long timeout = 100000L;
constexpr unsigned tohost = 0x80001000;

void tick()
{
    top->clock = 1;
    top->eval();
#if VM_TRACE
    if (tfp)
        tfp->dump((double)main_time);
#endif // VM_TRACE
    main_time++;

    top->io_nasti_aw_ready = mem->aw_ready();
    top->io_nasti_ar_ready = mem->ar_ready();
    top->io_nasti_w_ready = mem->w_ready();
    top->io_nasti_b_valid = mem->b_valid();
    top->io_nasti_b_bits_id = mem->b_id();
    top->io_nasti_b_bits_resp = mem->b_resp();
    top->io_nasti_r_valid = mem->r_valid();
    top->io_nasti_r_bits_id = mem->r_id();
    top->io_nasti_r_bits_resp = mem->r_resp();
    top->io_nasti_r_bits_last = mem->r_last();
    memcpy(&top->io_nasti_r_bits_data, mem->r_data(), 8);

    mem->tick(
        top->reset,
        top->io_nasti_ar_valid,
        top->io_nasti_ar_bits_addr,
        top->io_nasti_ar_bits_id,
        top->io_nasti_ar_bits_size,
        top->io_nasti_ar_bits_len,

        top->io_nasti_aw_valid,
        top->io_nasti_aw_bits_addr,
        top->io_nasti_aw_bits_id,
        top->io_nasti_aw_bits_size,
        top->io_nasti_aw_bits_len,

        top->io_nasti_w_valid,
        top->io_nasti_w_bits_strb,
        &top->io_nasti_w_bits_data,
        top->io_nasti_w_bits_last,

        top->io_nasti_r_ready,
        top->io_nasti_b_ready);

    top->clock = 0;
    top->eval();
#if VM_TRACE
    if (tfp)
        tfp->dump((double)main_time);
#endif // VM_TRACE
    main_time++;
}

std::vector<std::string> g_traceVec;

int verify(Cpu *cpu, VTile *top)
{
    if (!top->io_trace_wb_valid || top->io_trace_wb_busy)
        return 0;

    cpu->Execute();
    TraceInfo traceInfo = cpu->GetTraceInfo();

    std::stringstream ss;
    ss << std::hex << std::showbase << std::setfill('0');
    ss << "[ref] pc="
       << (uint32_t)traceInfo.pc
       << ", etype="
       << (uint32_t)traceInfo.etype
       << ", rf_wen="
       << (uint32_t)traceInfo.rf_wen
       << ", rf_widx="
       << (uint32_t)traceInfo.rf_widx
       << ", rf_wdata="
       << (uint32_t)traceInfo.rf_wdata
       << "; "
       << "[cpu] valid="
       << (uint32_t)top->io_trace_wb_valid
       << ", busy="
       << (uint32_t)top->io_trace_wb_busy
       << ", pc="
       << (uint32_t)top->io_trace_wb_pc
       << ", inst="
       << (uint32_t)top->io_trace_wb_inst
       << ", etype="
       << (uint32_t)top->io_trace_wb_cause
       << ", rf_wen="
       << (uint32_t)top->io_trace_wb_rf_wen
       << ", rf_widx="
       << (uint32_t)top->io_trace_wb_rf_widx
       << ", rf_wdata="
       << (uint32_t)top->io_trace_wb_rf_wdata;

    g_traceVec.push_back(ss.str());

    if (traceInfo.pc != top->io_trace_wb_pc)
        return -1;

    bool has_expt = top->io_trace_wb_expt;
    bool ref_has_expt = traceInfo.etype != ExceptionType::OK;

    if (has_expt != ref_has_expt)
    {
        return -1;
    }

    if (has_expt || ref_has_expt)
    {
        if (traceInfo.etype != top->io_trace_wb_cause)
            return -1;
    }

    if (traceInfo.rf_wen != top->io_trace_wb_rf_wen)
        return -1;

    if (traceInfo.rf_wen && (traceInfo.rf_widx != top->io_trace_wb_rf_widx))
        return -1;
    else if (traceInfo.rf_widx != 0 && traceInfo.rf_wdata != top->io_trace_wb_rf_wdata)
        return -1;

    return 0;
}

int main(int argc, char **argv)
{
    if (argc != 3)
        LOG_FATAL("usage: ./VTile binfile vcdfile");

    std::ifstream fs(argv[1], std::ios::binary);
    if (!fs.is_open())
        LOG_FATAL("file open fail, file path:%s", argv[1]);

    fs.seekg(0, std::ios::end);
    std::streamsize size = fs.tellg();
    fs.seekg(0, std::ios::beg);

    char *binary = new char[size];
    fs.read(binary, size);

    Verilated::commandArgs(argc, argv);  // Remember args
    top = new VTile;                     // target design
    mem = new mm_magic_t(128L << 20, 8); // target memory
    memcpy(mem->get_data(), binary, size);

    cpu = new Cpu;
    cpu->LoadBinary((uint8_t *)binary, size);

#if VM_TRACE                      // If verilator was invoked with --trace
    Verilated::traceEverOn(true); // Verilator must compute traced signals
    VL_PRINTF("Enabling waves...\n");
    tfp = new VerilatedVcdC;
    top->trace(tfp, 99);                        // Trace 99 levels of hierarchy
    tfp->open(argc > 2 ? argv[2] : "dump.vcd"); // Open the dump file
#endif

    cout << "Starting simulation!\n";

    // reset
    top->reset = 1;
    for (size_t i = 0; i < 5; i++)
    {
        tick();
    }

    // start
    top->reset = 0;
    top->io_host_fromhost_bits = 0;
    top->io_host_fromhost_valid = 0;
    do
    {
        tick();
        if (verify(cpu, top) != 0)
        {
            for (int i = 0; i < g_traceVec.size(); i++)
            {
                std::cout << "index=" << i << "; " << g_traceVec[i] << std::endl;
            }

            // return EXIT_FAILURE;
            break;
        }
    } while (!top->io_host_tohost && main_time < timeout);

    int retcode = top->io_host_tohost + 1;

    // Run for 10 more clocks
    for (size_t i = 0; i < 10; i++)
    {
        tick();
    }

    if (main_time >= timeout)
    {
        cerr << "Simulation terminated by timeout at time " << main_time
             << " (cycle " << main_time / 10 << ")" << endl;
        return EXIT_FAILURE;
    }
    else
    {
        cerr << "Simulation completed at time " << main_time << " (cycle " << main_time / 10 << ")" << endl;
        cerr << std::hex << "TOHOST = " << retcode << endl;
    }

#if VM_TRACE
    if (tfp)
        tfp->close();
    delete tfp;
#endif

    // unsigned gp = *reinterpret_cast<unsigned *>(mem->read(tohost).data());
    cout << "Finishing simulation! " << ((retcode == 0) ? "PASS" : "FAIL") << " retcode=" << retcode << "\n";

    delete top;
    delete mem;
    delete cpu;
    delete[] binary;

    return retcode == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}
